(ns avclj
  (:require [tech.v3.datatype :as dtype]
            [tech.v3.datatype.struct :as dt-struct]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.tensor :as dtt]
            [tech.v3.io :as io]
            [avclj.ffi :as av-ffi]
            [avclj.pixfmt :as av-pixfmt]
            [avclj.av-context :as av-ctx]
            [avclj.av-error :as av-error])
  (:import [java.io OutputStream]
           [java.util Map]))


(set! *warn-on-reflection* true)


(defprotocol PVideoEncoder
  (encode-frame! [enc frame]
    "Handle a frame of data.  Data frames are things for with
tech.v3.tensor/ensure-tensor returns a valid tensor.  That tensor
must have data in the same format as the pixfmt specified in
make-video-encoder."))


(defn initialize!
  []
  (av-ffi/initialize!))


(defn encoder-names
  []
  (->> (av-ffi/list-codecs)
       (filter :encoder?)
       (map :name)
       (sort)))


(defn decoder-names
  []
  (->> (av-ffi/list-codecs)
       (filter :decoder?)
       (map :name)
       (sort)))


(defn list-codecs
  []
  (av-ffi/list-codecs))


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


(deftype Encoder [^Map ctx ^Map frame ^Map packet ^OutputStream ostream pixfmt
                  ^:unsynchronized-mutable n-frames]
  PVideoEncoder
  (encode-frame! [this frame-data]
    (if frame-data
      (let [_ (av-ffi/av_frame_make_writable frame)
            ftens (raw-frame->tensors frame)
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
        (.put frame :pts n-frames)
        (set! n-frames (inc n-frames))
        (av-ffi/avcodec_send_frame ctx frame))
      (av-ffi/avcodec_send_frame ctx nil))
    (loop [pkt-retval (long (av-ffi/avcodec_receive_packet ctx packet))]
      (when-not (or (== pkt-retval av-error/AVERROR_EAGAIN)
                    (== pkt-retval av-error/AVERROR_EOF))
        (av-ffi/check-error pkt-retval)
        (.write ostream (-> (native-buffer/wrap-address
                             (.get packet :data)
                             (.get packet :size)
                             packet)
                            (dtype/->byte-array)))
        (av-ffi/av_packet_unref packet)
        (recur (long (av-ffi/avcodec_receive_packet ctx packet))))))
  java.lang.AutoCloseable
  (close [this]
    (encode-frame! this nil)
    (.close ^OutputStream ostream)
    (av-ffi/free-context ctx)
    (av-ffi/free-packet packet)
    (av-ffi/free-frame frame)))


(defn make-video-encoder
  (^java.lang.AutoCloseable
   [height width ostream {:keys [bit-rate gop-size max-b-frames
                                 fps-numerator fps-denominator pixfmt
                                 encoder-name-or-id]
                          :or {bit-rate 400000
                               gop-size 10
                               max-b-frames 1
                               ;;25 frames/sec
                               fps-numerator 25
                               fps-denominator 1
                               ;;BGR24 because byte-bgr is a bufferedimage supported
                               ;;format.
                               pixfmt "AV_PIX_FMT_BGR24"
                               encoder-name-or-id "mpeg4"}}]
   (let [pixfmt-num (av-pixfmt/pixfmt->value pixfmt)
         output (io/output-stream! ostream)
         ctx (av-ffi/alloc-context)
         pkt (av-ffi/alloc-packet)
         frame (av-ffi/alloc-frame)
         codec (if (string? encoder-name-or-id)
                 (av-ffi/find-encoder-by-name encoder-name-or-id)
                 (av-ffi/find-encoder (long encoder-name-or-id)))
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
     (.put ctx :pix-fmt pixfmt-num)
     (.put frame :format pixfmt-num)
     (.put frame :width width)
     (.put frame :height height)
     (try
       (clojure.pprint/pprint (into {} ctx))
       (clojure.pprint/pprint codec)
       (av-ffi/avcodec_open2 ctx (:codec codec) nil)
       ;;allocate framebuffer
       ;;We do not care about alignment
       (av-ffi/av_frame_get_buffer frame 0)
       (Encoder. ctx frame pkt output pixfmt 0)
       (catch Throwable e
         (.close output)
         (av-ffi/free-context ctx)
         (av-ffi/free-packet pkt)
         (av-ffi/free-frame frame)
         (throw e)))))
  (^java.lang.AutoCloseable
   [height width output-fname]
   (make-video-encoder height width output-fname)))
