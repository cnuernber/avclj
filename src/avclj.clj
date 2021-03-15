(ns avclj
  (:require [tech.v3.datatype :as dtype]
            [tech.v3.datatype.struct :as dt-struct]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.tensor :as dtt]
            [tech.v3.io :as io]
            [avclj.avcodec :as avcodec]
            [avclj.swscale :as swscale]
            [avclj.av-pixfmt :as av-pixfmt]
            [avclj.av-error :as av-error]
            [clojure.tools.logging :as log])
  (:import [java.io OutputStream]
           [java.util Map]
           [tech.v3.datatype.ffi Pointer]))


(set! *warn-on-reflection* true)


(defprotocol PVideoEncoder
  (encode-frame! [enc frame]
    "Handle a frame of data.  Data frames are either single tensors for interleaved
formats or sequences of tensors for planar formats - see make-video-encoder."))


(defn initialize!
  []
  (if (dt-ffi/find-library "x264")
    (log/debug "h264 encoding enabled")
    (log/debug "h264 encoding disabled"))
  (avcodec/initialize!)
  (swscale/initialize!))


(defn encoder-names
  []
  (->> (avcodec/list-codecs)
       (filter :encoder?)
       (map :name)
       (sort)))


(defn decoder-names
  []
  (->> (avcodec/list-codecs)
       (filter :decoder?)
       (map :name)
       (sort)))


(defn list-codecs
  []
  (avcodec/list-codecs))


(defn list-pix-formats
  []
  (->> (keys av-pixfmt/pixfmt-name-value-map)
       (sort)))


(defn raw-frame->tensors
  [^Map frame]
  (let [fheight (long (.get frame :height))]
    ;;frames may be up to 8 planes of data
    (->> (range 8)
         (map (fn [idx]
                (let [linesize (long (.get frame [:linesize idx]))]
                  (when-not (== 0 linesize)
                    (let [data-ptr (long (.get frame [:data idx]))
                          nbuf (native-buffer/wrap-address data-ptr
                                                           (* fheight linesize)
                                                           frame)]
                      (dtt/reshape nbuf [fheight linesize]))))))
         (remove nil?))))


(defn struct-member-ptr
  [data-struct member]
  (let [data-ptr (dt-ffi/->pointer data-struct)
        member-offset (-> (dt-struct/offset-of
                           (dt-struct/get-struct-def (dtype/datatype data-struct))
                           member)
                          ;;offset-of returns a pair of offset,datatype
                          (first))]
    (Pointer. (+ (.address data-ptr) member-offset))))


(deftype Encoder [^Map ctx ^Map packet ^OutputStream ostream
                  ^long input-pixfmt ^Map input-frame
                  ^long encoder-pixfmt ^Map encoder-frame
                  ^:unsynchronized-mutable n-frames
                  ^Map sws-ctx ]
  PVideoEncoder
  (encode-frame! [this frame-data]
    (if frame-data
      (let [_ (avcodec/av_frame_make_writable input-frame)
            ftens (raw-frame->tensors input-frame)
            frame-data (if (dtt/tensor? frame-data)
                         [frame-data]
                         frame-data)]
        (errors/when-not-errorf
         (== (count ftens) (count frame-data))
         "Count of frame data tensors (%d) differs from count of encode data tensors (%d)
This can happen when a planar format is chosen -- each plane is represented by one
tensor as they have different shapes.
Frame tensor shapes: %s
Input data shapes: %s"
         (count ftens)
         (count frame-data)
         (mapv dtype/shape ftens)
         (mapv dtype/shape frame-data))
        (doseq [[ftens input-tens] (map vector ftens frame-data)]
          (dtype/copy! input-tens ftens))
        (set! n-frames (inc n-frames))
        (if-not encoder-frame
          (do
            (.put input-frame :pts (dec n-frames))
            (avcodec/avcodec_send_frame ctx input-frame))
          (do
            (avcodec/av_frame_make_writable encoder-frame)
            (swscale/sws_scale sws-ctx
                               (struct-member-ptr input-frame :data)
                               (struct-member-ptr input-frame :linesize)
                               0 (:height input-frame)
                               (struct-member-ptr encoder-frame :data)
                               (struct-member-ptr encoder-frame :linesize))
            (.put encoder-frame :pts (dec n-frames))
            (avcodec/avcodec_send_frame ctx encoder-frame))))
      (do
        (avcodec/avcodec_send_frame ctx nil)))
    (loop [pkt-retval (long (avcodec/avcodec_receive_packet ctx packet))]
      (when-not (or (== pkt-retval av-error/AVERROR_EAGAIN)
                    (== pkt-retval av-error/AVERROR_EOF))
        (avcodec/check-error pkt-retval)
        (.write ostream (-> (native-buffer/wrap-address
                             (.get packet :data)
                             (.get packet :size)
                             packet)
                            (dtype/->byte-array)))
        (avcodec/av_packet_unref packet)
        (recur (long (avcodec/avcodec_receive_packet ctx packet))))))
  java.lang.AutoCloseable
  (close [this]
    (encode-frame! this nil)
    (.close ^OutputStream ostream)
    (avcodec/free-context ctx)
    (avcodec/free-packet packet)
    (avcodec/free-frame input-frame)
    (when-not (== input-pixfmt encoder-pixfmt)
      (avcodec/free-frame encoder-frame)
      (swscale/sws_freeContext sws-ctx))))


(defn make-video-encoder
  (^java.lang.AutoCloseable
   [height width ostream
    {:keys [bit-rate gop-size max-b-frames
            fps-numerator fps-denominator
            input-pixfmt encoder-pixfmt
            encoder-name-or-id]
     :or {bit-rate 400000
          gop-size 10
          max-b-frames 1
          ;;25 frames/sec
          fps-numerator 25
          fps-denominator 1
          ;;BGR24 because :byte-bgr is a bufferedimage supported format.
          input-pixfmt "AV_PIX_FMT_BGR24"
          ;;Lots of encoders *only* support this
          ;;input pixel format
          encoder-pixfmt "AV_PIX_FMT_YUV420P"
          encoder-name-or-id "mpeg4"}}]
   (let [input-pixfmt-num (av-pixfmt/pixfmt->value input-pixfmt)
         encoder-pixfmt-num (av-pixfmt/pixfmt->value encoder-pixfmt)
         output (io/output-stream! ostream)
         ctx (avcodec/alloc-context)
         pkt (avcodec/alloc-packet)
         input-frame (avcodec/alloc-frame)
         encoder-frame (when-not (== input-pixfmt-num encoder-pixfmt-num)
                         (avcodec/alloc-frame))
         sws-ctx (when encoder-frame
                   (swscale/sws_getContext width height input-pixfmt-num
                                           width height encoder-pixfmt-num
                                           swscale/SWS_BILINEAR nil nil nil))
         codec (if (string? encoder-name-or-id)
                 (avcodec/find-encoder-by-name encoder-name-or-id)
                 (avcodec/find-encoder (long encoder-name-or-id)))
         framerate (dt-struct/new-struct :av-rational)
         time-base (dt-struct/new-struct :av-rational)]
     (.put framerate :num fps-numerator)
     (.put framerate :den fps-denominator)
     (.put time-base :den fps-numerator)
     (.put time-base :num fps-denominator)
     (.put ctx :bit-rate bit-rate)
     (.put ctx :width width)
     (.put ctx :height height)
     (.put ctx :framerate framerate)
     (.put ctx :time-base time-base)
     (.put ctx :gop-size gop-size)
     (.put ctx :max-b-frames max-b-frames)
     (.put ctx :pix-fmt encoder-pixfmt-num)
     (.put input-frame :format input-pixfmt-num)
     (.put input-frame :width width)
     (.put input-frame :height height)
     (when encoder-frame
       (.put encoder-frame :format encoder-pixfmt-num)
       (.put encoder-frame :width width)
       (.put encoder-frame :height height))
     (try
       (avcodec/avcodec_open2 ctx (:codec codec) nil)
       ;;allocate framebuffer
       ;;We do not care about alignment
       (avcodec/av_frame_get_buffer input-frame 0)
       (when encoder-frame (avcodec/av_frame_get_buffer encoder-frame 0))
       (Encoder. ctx pkt output
                 input-pixfmt-num input-frame
                 encoder-pixfmt-num encoder-frame
                 0 sws-ctx)
       (catch Throwable e
         (.close output)
         (avcodec/free-context ctx)
         (avcodec/free-packet pkt)
         (avcodec/free-frame input-frame)
         (when encoder-frame (avcodec/free-frame encoder-frame))
         (when sws-ctx (swscale/sws_freeContext sws-ctx))
         (throw e)))))
  (^java.lang.AutoCloseable
   [height width output-fname]
   (make-video-encoder height width output-fname nil)))
