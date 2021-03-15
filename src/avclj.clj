(ns avclj
  "libavcodec (FFMPEG) bindings for Clojure.

```clojure

user> (require '[avclj :as avclj])
nil
user> (avclj/initialize!)
:ok
user> (require '[tech.v3.tensor :as dtt])
nil
user> (defn img-tensor
  [shape ^long offset]
  (dtt/compute-tensor shape
                      (fn [^long y ^long x ^long c]
                        (let [ymod (-> (quot (+ y offset) 32)
                                       (mod 2))
                              xmod (-> (quot (+ x offset) 32)
                                       (mod 2))]
                          (if (and (== 0 xmod)
                                   (== 0 ymod))
                            255
                            0)))
                      :uint8))
#'user/img-tensor
nil
user> (let [output-fname \"file://test/data/test-video.mp4\"]
        (with-open [encoder (avclj/make-video-encoder 256 256 output-fname
                                                      {:encoder-name-or-id \"mpeg4\"})]
          (dotimes [iter 125]
            (avclj/encode-frame! encoder (img-tensor [256 256 3] iter)))))
nil
```"
  (:require [tech.v3.datatype :as dtype]
            [tech.v3.datatype.struct :as dt-struct]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.dechunk-map :refer [dechunk-map]]
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
  "Initialize the library.  Dynamically must find libavcodec and libswscale.  Attempts
  to load x264 to enable h264 encode."
  []
  (if (dt-ffi/find-library "x264")
    (log/debug "h264 encoding enabled")
    (log/debug "h264 encoding disabled"))
  (avcodec/initialize!)
  (swscale/initialize!)
  :ok)


(defn encoder-names
  "List all of the encoder names"
  []
  (->> (avcodec/list-codecs)
       (filter :encoder?)
       (map :name)
       (sort)))


(defn decoder-names
  "List all decoder names"
  []
  (->> (avcodec/list-codecs)
       (filter :decoder?)
       (map :name)
       (sort)))


(defn list-codecs
  "List all available encoder/decoders"
  []
  (avcodec/list-codecs))


(defn list-pix-formats
  "List all available pixel format names"
  []
  (->> (keys av-pixfmt/pixfmt-name-value-map)
       (sort)))


(defn raw-frame->tensors
  "Zero-copy conversion ofa frame to a vector of tensors, one for each of the
  frame's data planes."
  [^Map frame]
  (let [fheight (long (.get frame :height))]
    ;;frames may be up to 8 planes of data
    (->> (range 8)
         (dechunk-map (juxt identity #(.get frame [:linesize %])))
         (take-while #(> (long (second %)) 0))
         (mapv (fn [[idx linesize]]
                 (let [linesize (long linesize)]
                  (when-not (== 0 linesize)
                    (let [data-ptr (long (.get frame [:data idx]))
                          nbuf (-> (native-buffer/wrap-address data-ptr
                                                               (* fheight linesize)
                                                               frame)
                                   (native-buffer/set-native-datatype :uint8))]
                      (dtt/reshape nbuf [fheight linesize])))))))))


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
                               (dt-ffi/struct-member-ptr input-frame :data)
                               (dt-ffi/struct-member-ptr input-frame :linesize)
                               0 (:height input-frame)
                               (dt-ffi/struct-member-ptr encoder-frame :data)
                               (dt-ffi/struct-member-ptr encoder-frame :linesize))
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
  "Make a video encoder.

  * `height` - divisible by 2
  * `width` - divisible by 2
  * `ostream`  - convertible via tech.v3.io/output-stream! to an output stream.

  Selected Options:

  * `:input-pixfmt` - One of the pixel formats.  Defaults to \"AV_PIX_FMT_BGR24\"
  * `:encoder-pixfmt` - One of the pixel formats.  Defaults to \"AV_PIX_FMT_YUV420P\".
     Changing this will probably cause opending the codec to fail with an
     invalid argument.
  * `:fps-numerator` - :int32 defaults to 25.
  * `:fps-denominator` - :int32 defaults to 1."
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
