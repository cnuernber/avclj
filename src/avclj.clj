(ns avclj
  "libavcodec (FFMPEG) bindings for Clojure.

```clojure
user> (require '[avclj :as avclj])
nil
user> (require '[avclj.av-codec-ids :as codec-ids])
nil
user> (require '[tech.v3.tensor :as dtt])
nil
user> (avclj/initialize!)
:ok
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
user> (let [encoder-name codec-ids/AV_CODEC_ID_H264
            output-fname \"test/data/test-video.mp4\"]
        (with-open [encoder (avclj/make-video-encoder 256 256 output-fname
                                                      {:encoder-name encoder-name})]
          (dotimes [iter 125]
            (avclj/encode-frame! encoder (img-tensor [256 256 3] iter)))))
nil
```


  * To use this with buffered images, make sure the pixel formats match and be sure
    to require `tech.v3.libs.buffered-image`.
  * If you have a system that is producing java.nio.ByteBuffers then require
    `tech.v3.datatype.nio-buffer`.


  For manipulating h264 encoder properties, use the `:codec-private-options` and
  the various possibilities from the [ffmpeg libx264 page](https://trac.ffmpeg.org/wiki/Encode/H.264).
  "

  (:require [tech.v3.datatype :as dtype]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.struct :as dt-struct]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.ffi.size-t :as dt-ffi-size-t]
            [tech.v3.tensor :as dtt]
            [tech.v3.datatype.dechunk-map :refer [dechunk-map]]
            [avclj.avcodec :as avcodec]
            [avclj.swscale :as swscale]
            [avclj.avformat :as avformat]
            [avclj.avutil :as avutil]
            [avclj.av-pixfmt :as av-pixfmt]
            [avclj.av-error :as av-error]
            [avclj.av-context :as av-context]
            [tech.v3.resource :as resource]
            [clojure.tools.logging :as log]
            [clojure.java.io :as clj-io])
  (:import [java.io OutputStream]
           [java.util Map]
           [tech.v3.datatype.ffi Pointer]
           [clojure.lang IMeta]))


(set! *warn-on-reflection* true)


(defprotocol PVideoEncoder
  (encode-frame! [enc frame]
    "Handle a frame of data.  A frame is a persistent vector of buffers, one for
each data plane.  If you are passing in a nio buffer, ensure to require
'tech.v3.datatype.nio-buffer` for zero-copy support.  If frame is not a persistent
vector it is assumed to a single buffer and is wrapped in a persistent vector"))


(defprotocol PVideoDecoder
  (decode-frame! [decoder]
    "Decode a frame returning a persistent vector of buffers, one for each data plane
in the frame.  When this function returns nil there are no more frames to decode.
Users can expect the frames will be decoded in-place so the data is only valid until
the next decode-frame! call"))


(defn initialize!
  "Initialize the library.  Dynamically must find libavcodec and libswscale.  Attempts
  to load x264 to enable h264 encode."
  []
  (if (dt-ffi/find-library "x264")
    (log/debug "h264 encoding enabled")
    (log/debug "h264 encoding disabled"))
  (avcodec/initialize!)
  (swscale/initialize!)
  (avformat/initialize!)
  (avutil/initialize!)
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


(defn find-encoder
  "Find an encoder by name.  Name may either be the ffmpeg encoder name
  such as \"libx264\" or it may be a codec id in `avclj.av-codec-ids` such as
  `avclj.av-codec-ids/AV_CODEC_ID_H264`."
  [encoder-name]
  (if (string? encoder-name)
    (avcodec/find-encoder-by-name encoder-name)
    (avcodec/find-encoder (long encoder-name))))


(defn find-decoder
  "Find an encoder by name.  Name may either be the ffmpeg encoder name
  such as \"libx264\" or it may be a codec id in `avclj.av-codec-ids` such as
  `avclj.av-codec-ids/AV_CODEC_ID_H264`."
  [decoder-name]
  (if (string? decoder-name)
    (avcodec/find-decoder-by-name decoder-name)
    (avcodec/find-decoder (long decoder-name))))


(defn list-pix-formats
  "List all available pixel format names."
  []
  (->> (keys av-pixfmt/pixfmt-name-value-map)
       (sort)))


(defn- pixfmt->n-bytes
  [^long pixfmt]
  (condp = pixfmt
    av-pixfmt/AV_PIX_FMT_RGB24 3
    av-pixfmt/AV_PIX_FMT_BGR24 3
    av-pixfmt/AV_PIX_FMT_ARGB 4
    av-pixfmt/AV_PIX_FMT_RGBA 4
    av-pixfmt/AV_PIX_FMT_ABGR 4
    av-pixfmt/AV_PIX_FMT_BGRA 4
    av-pixfmt/AV_PIX_FMT_GRAY8 1
    nil))


(defn- fix-frame-padding
  [data ^long width pixfmt]
  (if-let [n-channels (pixfmt->n-bytes pixfmt)]
    (let [cropsize (* width n-channels)
          [_ dlinesize] (dtype/shape data)]
      (->
       (if (not= cropsize dlinesize)
         (dtt/select data :all (range cropsize))
         data)
       ;;reshape to be more image like
       (dtt/reshape [(first (dtype/shape data)) width n-channels])))
    data))


(defn- raw-frame->buffers
  "Zero-copy conversion ofa frame to a vector of buffers, one for each of the
  frame's data planes."
  [^Map frame]
  (let [fheight (long (.get frame :height))
        fwidth (long (.get frame :width))
        pixfmt (long (.get frame :format))]
    ;;frames may be up to 8 planes of data
    (->> (range 8)
         (dechunk-map (juxt identity #(.get frame [:linesize %])))
         (take-while #(> (long (second %)) 0))
         (mapv (fn [[idx linesize]]
                 (let [linesize (long linesize)]
                  (when-not (== 0 linesize)
                    (let [data-ptr (long (.get frame [:data idx]))]
                      (-> (native-buffer/wrap-address data-ptr
                                                      (* fheight linesize)
                                                      frame)
                          (native-buffer/set-native-datatype :uint8)
                          (dtt/reshape [fheight linesize])
                          (fix-frame-padding fwidth pixfmt))))))))))


(defn frame-buffer-shape
  "Return a vector of buffer shapes.  Corresponds to the require input format of
  encode-frame!.  Note that for planar pixel formats you will have to pass in
  multiple buffers."
  [height width pixel-fmt]
  (let [frame (avcodec/alloc-frame)]
    (try
      (.put frame :width width)
      (.put frame :height height)
      (.put frame :format (av-pixfmt/pixfmt->value pixel-fmt))
      (avcodec/av_frame_get_buffer frame 0)
      (avcodec/av_frame_make_writable frame)
      (mapv dtype/shape (raw-frame->buffers frame))
      (finally
        (avcodec/free-frame frame)))))


(defn- rescale
  ^long [^long value src-timebase dst-timebase]
  (let [mult (* (long (:num src-timebase)) (long (:den dst-timebase)))
        div (* (long (:den src-timebase)) (long (:num dst-timebase)))]
    (avutil/av_rescale value mult div)))


(def ^{:tag 'long
       :private true} AV_NOPTS_VALUE (unchecked-long 0x8000000000000000))


(deftype Encoder [^Map ctx ^Map packet
                  ^long input-pixfmt ^Map input-frame
                  ^long encoder-pixfmt ^Map encoder-frame
                  ^:unsynchronized-mutable n-frames
                  ^Map sws-ctx ^Map avfmt-ctx ^Map stream]
  PVideoEncoder
  (encode-frame! [this frame-data]
    (if frame-data
      (let [_ (avcodec/av_frame_make_writable input-frame)
            ftens (raw-frame->buffers input-frame)
            frame-data (if (vector? frame-data)
                         frame-data
                         [frame-data])]
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
      (avcodec/avcodec_send_frame ctx nil))
    (loop [pkt-retval (long (avcodec/avcodec_receive_packet ctx packet))]
      (when-not (or (== pkt-retval av-error/AVERROR_EAGAIN)
                    (== pkt-retval av-error/AVERROR_EAGAIN_MAC)
                    (== pkt-retval av-error/AVERROR_EOF))
        (avcodec/check-error nil pkt-retval)
        ;;The packet is in the time-base we specified originally for the context but
        ;;the stream's time-base was set during write-header and must be
        ;;respected so we have to convert to the stream's time-base
        (.put packet :duration 1)
        (when-not (== (long (:pts packet)) AV_NOPTS_VALUE)
          (.put packet :pts (rescale (:pts packet)
                                     (:time-base ctx)
                                     (:time-base stream))))
        (when-not (== (long (:pts packet)) AV_NOPTS_VALUE)
          (.put packet :dts (rescale (:dts packet)
                                     (:time-base ctx)
                                     (:time-base stream))))
        (when-not (== (long (:duration packet)) 0)
          (.put packet :duration (rescale (:duration packet)
                                          (:time-base ctx)
                                          (:time-base stream))))
        (when-not (== (long (:convergence-duration packet)) 0)
          (.put packet :convergence-duration (rescale (:convergence-duration packet)
                                                      (:time-base ctx)
                                                      (:time-base stream))))
        (avformat/av_interleaved_write_frame avfmt-ctx packet)
        (avcodec/av_packet_unref packet)
        (recur (long (avcodec/avcodec_receive_packet ctx packet))))))
  java.lang.AutoCloseable
  (close [this]
    (encode-frame! this nil)
    (avformat/av_write_trailer avfmt-ctx)
    (avcodec/free-context ctx)
    (avcodec/free-packet packet)
    (avcodec/free-frame input-frame)
    (when-not (== input-pixfmt encoder-pixfmt)
      (avcodec/free-frame encoder-frame)
      (swscale/sws_freeContext sws-ctx))
    (avformat/avio_closep (dt-ffi/struct-member-ptr avfmt-ctx :pb))
    (avformat/avformat_free_context avfmt-ctx)))


(defn- file-format-from-fname
  [^String fname]
  (let [idx (.lastIndexOf (str fname) ".")]
    (.substring fname (inc idx))))


(defn make-video-encoder
  "Make a video encoder.

  * `height` - divisible by 2
  * `width` - divisible by 2
  * `out-fname` - Output filepath.  Must be a c-addressable file path, not a url or
     an input stream.  The file format will be divined from the file extension.

  Selected Options:

  * `:input-pixfmt` - One of the pixel formats.  Defaults to \"AV_PIX_FMT_BGR24\"
  * `:encoder-pixfmt` - One of the pixel formats.  Defaults to \"AV_PIX_FMT_YUV420P\".
     Changing this will probably cause opening the codec to fail with an
     invalid argument.  To see the valid encoder pixel formats, use find-encoder
     and analyze the `:pix-fmts` member.
  * `:encoder-name` - Name (or integer codec id) of the encoder to use.
  * `:fps-numerator` - :int32 defaults to 60.
  * `:fps-denominator` - :int32 defaults to 1.
  * `:codec-options` - Map of string option name to string option key.
  * `:codec-private-options` - Codec-private options you can set.  For libx264 an example
     is {\"preset\" \"slow\"}.
  * `:bit-rate` - If specified, the system will set a constant bit-rate for the video.  In
    this case with h264 encoding it will switch from crf encoding to abr encoding with a
    40 frame rate control look-ahead."
  (^java.lang.AutoCloseable
   [height width out-fname
    {:keys [fps-numerator fps-denominator
            input-pixfmt encoder-pixfmt
            encoder-name
            file-format
            bit-rate
            codec-options
            codec-private-options]
     :or {;;60 frames/sec
          fps-numerator 60
          fps-denominator 1
          ;;BGR24 because :byte-bgr is a bufferedimage supported format.
          input-pixfmt "AV_PIX_FMT_BGR24"
          ;;Lots of encoders *only* support this
          ;;input pixel format
          encoder-pixfmt "AV_PIX_FMT_YUV420P"
          encoder-name "mpeg4"}}]
   (clj-io/make-parents out-fname)
   (let [input-pixfmt-num (if (string? input-pixfmt)
                            (av-pixfmt/pixfmt->value input-pixfmt)
                            (long input-pixfmt))
         encoder-pixfmt-num (if (string? encoder-pixfmt)
                              (av-pixfmt/pixfmt->value encoder-pixfmt)
                              (long encoder-pixfmt))
         file-format (or file-format (file-format-from-fname out-fname))
         codec (if (string? encoder-name)
                 (avcodec/find-encoder-by-name encoder-name)
                 (avcodec/find-encoder (long encoder-name)))
         _ (errors/when-not-errorf codec "Failed to find encoder: %s" encoder-name)
         codec-ptr (:codec codec)
         avfmt-ctx (avformat/alloc-output-context file-format)
         _ (avformat/avio_open2 (dt-ffi/struct-member-ptr avfmt-ctx :pb)
                                out-fname avformat/AVIO_FLAG_WRITE nil nil)
         ctx (avcodec/alloc-context codec-ptr)
         pkt (avcodec/alloc-packet)
         input-frame (avcodec/alloc-frame)
         encoder-frame (when-not (== input-pixfmt-num encoder-pixfmt-num)
                         (avcodec/alloc-frame))
         sws-ctx (when encoder-frame
                   (swscale/sws_getContext width height input-pixfmt-num
                                           width height encoder-pixfmt-num
                                           swscale/SWS_BILINEAR nil nil nil))
         stream (avformat/new-stream avfmt-ctx codec-ptr)
         framerate (dt-struct/new-struct :av-rational)
         time-base (dt-struct/new-struct :av-rational)]
     (.put framerate :num fps-numerator)
     (.put framerate :den fps-denominator)
     (.put time-base :den fps-numerator)
     (.put time-base :num fps-denominator)
     (.put ctx :width width)
     (.put ctx :height height)
     (.put ctx :framerate framerate)
     (.put ctx :time-base time-base)
     (when bit-rate (.put ctx :bit-rate bit-rate))
     (.put ctx :pix-fmt encoder-pixfmt-num)
     (.put input-frame :format input-pixfmt-num)
     (.put input-frame :width width)
     (.put input-frame :height height)
     (when encoder-frame
       (.put encoder-frame :format encoder-pixfmt-num)
       (.put encoder-frame :width width)
       (.put encoder-frame :height height))
     (try
       (let [opt-dict (when (seq codec-options)
                        (log/infof "Codec Options: %s" (pr-str codec-options))
                        (let [dict (avutil/alloc-dict)]
                          (doseq [[k v] codec-options]
                            (avutil/set-key-value! dict k v 0))))]
         (when (seq codec-private-options)
           (log/infof "Codec Private Options: %s" codec-private-options)
           (doseq [[k v] codec-private-options]
             (avcodec/av_opt_set (Pointer. (:priv-data ctx)) k v 0)))
         (avcodec/avcodec_open2 ctx codec-ptr opt-dict))
       (avcodec/avcodec_parameters_from_context
        (Pointer. (:codecpar stream)) ctx)
       (.put stream :avg-frame-rate framerate)
       (.put stream :time-base time-base)
       ;;!!This sets the time-base of the stream!!
       (avformat/avformat_write_header avfmt-ctx nil)
       ;;allocate framebuffer
       ;;We do not care about alignment
       (avcodec/av_frame_get_buffer input-frame 0)
       (when encoder-frame (avcodec/av_frame_get_buffer encoder-frame 0))
       (Encoder. ctx pkt
                 input-pixfmt-num input-frame
                 encoder-pixfmt-num encoder-frame
                 0 sws-ctx avfmt-ctx stream)
       (catch Throwable e
         (avcodec/free-context ctx)
         (avcodec/free-packet pkt)
         (avcodec/free-frame input-frame)
         (avformat/avformat_free_context avfmt-ctx)
         (when encoder-frame (avcodec/free-frame encoder-frame))
         (when sws-ctx (swscale/sws_freeContext sws-ctx))
         (throw e)))))
  (^java.lang.AutoCloseable
   [height width output-fname]
   (make-video-encoder height width output-fname nil)))


(deftype VideoStreamDecoder [fmt-ctx decoder-ctx
                             pkt frame
                             ^long stream-index
                             ^:unsynchronized-mutable frame-stage
                             sws-ctx
                             ^Map sws-frame
                             metadata]
  IMeta
  (meta [this] metadata)
  java.lang.AutoCloseable
  (close [this]
    (avcodec/free-packet pkt)
    (avcodec/free-frame frame)
    (avcodec/free-context decoder-ctx)
    (when sws-ctx
      (avcodec/free-frame sws-frame)
      (swscale/sws_freeContext sws-ctx))
    (resource/stack-resource-context
     (let [ctx-ptr (->> (dt-ffi/->pointer fmt-ctx)
                        (.address)
                        (dt-ffi/make-ptr :pointer))]
       (avformat/avformat_close_input ctx-ptr))))

  PVideoDecoder
  (decode-frame! [this]
    (when (or (identical? :decoding frame-stage)
              (identical? :finalizing frame-stage))
      (avcodec/av_frame_unref frame))
    (loop [next-frame nil]
      (if (and (nil? next-frame)
               (not= frame-stage :finished))
        (recur
         (case frame-stage
           :reading
           (let [frame-ret (long (avformat/av_read_frame fmt-ctx pkt))]
             (if (< frame-ret 0)
               (do
                 (avcodec/avcodec_send_packet decoder-ctx nil)
                 (set! frame-stage :finalizing)
                 nil)
               (do
                 (when (== (long (pkt :stream-index)) stream-index)
                   (do
                     (set! frame-stage :decoding)
                     (avcodec/avcodec_send_packet decoder-ctx pkt)))
                 (avcodec/av_packet_unref pkt)
                 nil)))
           (:decoding :finalizing)
           (let [res (avcodec/avcodec_receive_frame decoder-ctx frame)]
             (if (< res 0)
               (do (if (identical? frame-stage :decoding)
                     (set! frame-stage :reading)
                     (set! frame-stage :finished))
                   nil)
               (->
                (if sws-ctx
                  (do
                    (avcodec/av_frame_make_writable sws-frame)
                    (swscale/sws_scale sws-ctx
                                       (dt-ffi/struct-member-ptr frame :data)
                                       (dt-ffi/struct-member-ptr frame :linesize)
                                       0 (:height frame)
                                       (dt-ffi/struct-member-ptr sws-frame :data)
                                       (dt-ffi/struct-member-ptr sws-frame :linesize))
                    (raw-frame->buffers sws-frame))
                  (raw-frame->buffers frame))
                (with-meta {:pts (frame :pts)
                            :width (sws-frame :width)
                            :height (sws-frame :height)
                            :linesize (sws-frame :linesize)}))))
           :finished
           nil))
        next-frame))))


(defn make-video-decoder
  "Make an auto-closeable video decoder - you can use this decoder with `with-open`.
  Do not assume that you can keep a reference to the frame data; it is only good
  until the next `decode-frame!` call.  The decoder will do simple scaling and pixel
  format translations using highly optimized ffmpeg code.

  The decoder returns a frame of data which in ffmpeg terms means a sequence of buffers.
  For the RGB-based pixel formats the first data-frame will be a tensor reshaped to the
  appropriate size.



  :Options

  * `:output-pixfmt` - a valid ffmpeg pixfmt string.  Defaults to `\"AV_PIX_FMT_BGR24\"` as this
     corresponds to buffered-image :byte-bgr format.  For list of valid pixfmt strings
     see `(keys avclj.av-pixfmt/pixvmt-name-value-map)`.  I recommend sticking to
     either `\"AV_PIX_FMT_RGB24\"` or `\"AV_PIX_FMT_BGR24\"`.
  * `:output-height`, `:output-width` - If none are specified, use video width/height.  If
    one is specified use aspect-ratio-preserving scaling to find the other.  If both are
    specified use these precisely.  This may distort the image.

  Example:

```clojure
user> (require '[tech.v3.datatype :as dtype])
nil
user> (require '[tech.v3.libs.buffered-image :as bufimg])
nil
user> (require '[avclj :as avclj])
nil
user> (avclj/initialize!)
:ok
user> (def decoder (avclj/make-video-decoder \"test/data/test-video.mp4\"))
#'user/decoder
user> (meta decoder)
{:time-base {:num 1, :den 15360},
 :width 256,
 :height 256,
 :pix-fmt [\"AV_PIX_FMT_BGR24\"]}
user> ;;frame data is a sequence of buffers, the exact format is dependent upon pix-fmt
user> (def frame-data (avclj/decode-frame! decoder))
#'user/frame-data
user> frame-data
[#native-buffer@0x00007F3C17383FC0<uint8>[196608]
[250, 253, 251, 250, 253, 251, 250, 253, 251, 250, 253, 251, 250, 253, 251, 250, 253, 251, 250, 253...]]
user> (meta frame-data)
{:pts 0, :width 256, :height 256}
user> ;;use (/ (* pts num) den) to get actual frame ms offset.
user> (def dest-img (bufimg/new-image ((meta decoder) :height) ((meta decoder) :width) :byte-bgr))
#'user/dest-img
user> (dtype/copy! (first frame-data) dest-img)
#object[java.awt.image.BufferedImage 0x115e9eaf \"BufferedImage@115e9eaf: type = 5 ColorModel: #pixelBits = 24 numComponents = 3 color space = java.awt.color.ICC_ColorSpace@154aafe6 transparency = 1 has alpha = false isAlphaPre = false ByteInterleavedRaster: width = 256 height = 256 #numDataElements 3 dataOff[0] = 2\"]
user> (bufimg/save! dest-img \"test.png\")
true
user> (.close decoder)



```
  "
  [fname & [{:keys [output-pixfmt output-height output-width]
             :or {output-pixfmt "AV_PIX_FMT_BGR24"}}]]
  (let [file-fmt-ctx-ptr (dt-ffi/make-ptr :pointer 0)
        _ (errors/when-not-errorf
           (>= (avformat/avformat_open_input file-fmt-ctx-ptr fname nil nil) 0)
           "Failed to open file %s" fname)
        fmt-ctx  (dt-ffi/ptr->struct (:datatype-name @av-context/av-format-context-def*)
                                     (Pointer. (file-fmt-ctx-ptr 0)))]
    (try
      (errors/when-not-errorf
       (>= (avformat/avformat_find_stream_info fmt-ctx nil) 0)
       "Failed to find stream info")
      (let [codec-ptr (dt-ffi/make-ptr :pointer 0)
            stream-index (avformat/av_find_best_stream
                          fmt-ctx (avutil/media-type->int :video) -1 -1
                          codec-ptr 0)
            _ (errors/when-not-errorf
               (>= stream-index 0)
               "Failed to find best stream")
            codec (dt-ffi/ptr->struct (:datatype-name @av-context/codec-def*)
                                      (Pointer. (codec-ptr 0)))
            dec-ctx (avcodec/alloc-context codec)
            ptr-dt (dt-ffi-size-t/ptr-t-type)
            dt-size (casting/numeric-byte-width ptr-dt)
            n-streams (long (fmt-ctx :nb-streams))
            streams-ptr (native-buffer/wrap-address
                         (fmt-ctx :streams)
                         (* n-streams dt-size)
                         ptr-dt :little-endian nil)
            stream (dt-ffi/ptr->struct (:datatype-name @av-context/av-stream-def*)
                                       (Pointer. (streams-ptr stream-index)))
            _ (avcodec/avcodec_parameters_to_context dec-ctx (Pointer. (stream :codecpar)))
            _ (errors/when-not-errorf
               (>= (avcodec/avcodec_open2 dec-ctx codec nil) 0)
               "Failed to open decoder context")
            frame (avcodec/alloc-frame)
            pkt (avcodec/alloc-packet)
            decoder-pixfmt-num (dec-ctx :pix-fmt)
            output-pixfmt-num (long (if output-pixfmt
                                      (av-pixfmt/pixfmt->value output-pixfmt)
                                      decoder-pixfmt-num))
            dec-width (long (dec-ctx :width))
            dec-height (long (dec-ctx :height))
            [^long output-width ^long output-height]
            (cond
              (and output-width output-height)
              [output-width output-height]
              output-width
              [output-width (long (* dec-height (/ (long output-width) dec-width)))]
              output-height
              [(long (* dec-width (/ (long output-height) dec-height))) output-height]
              :else
              [dec-width dec-height])
            [sws-ctx output-frame]
            (when (or (not= output-pixfmt-num decoder-pixfmt-num)
                      (not= dec-width output-width)
                      (not= dec-height output-height))
              [(swscale/sws_getContext dec-width dec-height decoder-pixfmt-num
                                       output-width output-height output-pixfmt-num
                                       swscale/SWS_BILINEAR nil nil nil)
               (let [out-frame (avcodec/alloc-frame)]
                 (.put out-frame :format output-pixfmt-num)
                 (.put out-frame :width output-width)
                 (.put out-frame :height output-height)
                 (avcodec/av_frame_get_buffer out-frame 0)
                 out-frame)])]
        (VideoStreamDecoder. fmt-ctx dec-ctx pkt frame stream-index :reading
                             sws-ctx output-frame
                             ;;set the metadata on the decoder
                             {:time-base (stream :time-base)
                              :width output-width
                              :height output-height
                              :pix-fmt (av-pixfmt/value->pixfmt output-pixfmt-num)}))
      (catch Throwable e
        (avformat/avformat_close_input file-fmt-ctx-ptr)
        (throw e)))))
