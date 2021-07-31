(ns avclj.libavclj
  (:require [avclj :as avclj]
            [avclj.libavclj-init :as libinit]
            [avclj.av-codec-ids :as codec-ids]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.native-buffer :as nbuf])
  (:import [java.util.concurrent ConcurrentHashMap]
           [tech.v3.datatype.ffi Pointer]))


(defn initialize-avclj
  []
  (libinit/initialize-avclj))


(defn is-avclj-initialized
  []
  (libinit/is-avclj-initialized))


(defn make-h264-encoder
  [height width out-fname input-pix-format-str]
  (let [pix-str (dt-ffi/c->string input-pix-format-str)
        out-fname (dt-ffi/c->string out-fname)
        encoder (avclj/make-video-encoder
                 height width out-fname
                 {:input-pixfmt pix-str
                  :encoder-name codec-ids/AV_CODEC_ID_H264})
        handle (long (System/identityHashCode encoder))]
    (.put libinit/encoders handle encoder)
    handle))

(defn- hdl->encoder
  [hdl]
  (if-let [retval (.get libinit/encoders (long hdl))]
    retval
    (errors/throwf "Enable to find encoder 0x%x" (long hdl))))


(defn encode-frame
  [encoder-hdl frame-data frame-data-len]
  (let [nbuf (native-buffer/wrap-address
              (.address ^Pointer frame-data)
              frame-data-len nil)
        encoder (hdl->encoder encoder-hdl)]
    (avclj/encode-frame! encoder nbuf)
    1))


(defn close-encoder
  [encoder-hdl]
  (if-let [encoder (.get libinit/encoders (long encoder-hdl))]
    (do
      (.close ^java.lang.AutoCloseable encoder)
      (.remove libinit/encoders (long encoder-hdl))
      1)
    0))

(defn ptr->addr
  ^long [^Pointer ptr]
  (.address ptr))

(defn make-decoder
  [fname io-width io-height]
  (let [fname (dt-ffi/c->string fname)
        ;;int pointers are translated into native buffers of int width
        io-width (-> (nbuf/wrap-address (ptr->addr io-width) Integer/BYTES nil)
                     (nbuf/set-native-datatype :int32))
        io-height (-> (nbuf/wrap-address (ptr->addr io-height) Integer/BYTES nil)
                      (nbuf/set-native-datatype :int32))
        decoder (avclj/make-video-decoder
                 fname
                 (merge {:output-pixfmt "AV_PIX_FMT_RGB24"}
                        (when (> (io-width 0) 0)
                          {:output-width (io-width 0)})
                        (when (> (io-height 0) 0)
                          {:output-height (io-height 0)})))
        handle (long (System/identityHashCode decoder))
        {:keys [width height]} (meta decoder)]
    (.put libinit/encoders handle decoder)
    (dtype/set-value! io-width 0 width)
    (dtype/set-value! io-height 0 height)
    handle))


(defn decode-frame
  [decoder-hdl buffer]
  (if-let [decoder (.get libinit/encoders decoder-hdl)]
    (if-let [frame-data (avclj/decode-frame! decoder)]
      (let [{:keys [^long width ^long height]} (meta decoder)
            bufsize (* width height 3)
            buffer (native-buffer/wrap-address (ptr->addr buffer) bufsize nil)]
        (dtype/copy! (frame-data 0) buffer)
        1)
      0)
    -1))


(defn close-decoder
  [decoder-hdl]
  ;;both encoders and decoders are auto-closeable
  (close-encoder decoder-hdl))
