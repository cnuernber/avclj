(ns avclj.libavclj
  (:require [avclj :as avclj]
            [avclj.libavclj-init :as libinit]
            [avclj.av-codec-ids :as codec-ids]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.errors :as errors])
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


(comment
  (do
    (require '[tech.v3.datatype.ffi.graalvm :as graalvm])
    (with-bindings {#'*compile-path* "library/classes"}
      (graalvm/expose-clojure-functions
       ;;name conflict - initialize is too general
       {#'initialize-avclj {:rettype :int64}
        #'is-avclj-initialized {:rettype :int64}
        #'make-h264-encoder {:rettype :int64
                             :argtypes [['height :int64]
                                        ['width :int64]
                                        ['out-fname :pointer]
                                        ['input-pixfmt :pointer]]}
        #'encode-frame {:rettype :int64
                        :argtypes [['encoder :int64]
                                   ['frame-data :pointer]
                                   ['frame-data-len :int64]]}
        #'close-encoder {:rettype :int64
                         :argtypes [['encoder :int64]]}
        }
       'avljc.LibAVClj nil)))
  )
