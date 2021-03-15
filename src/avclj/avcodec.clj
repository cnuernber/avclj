(ns avclj.avcodec
  ;;Getting the context layout correct requires it's own file!!
  (:require [avclj.av-context :as av-context]
            [avclj.av-error :as av-error]
            [avclj.av-pixfmt :as av-pixfmt]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.ffi.size-t :as ffi-size-t]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.resource :as resource]
            [clojure.tools.logging :as log])
  (:import [tech.v3.datatype.ffi Pointer]
           [java.util Map]))


(def avcodec-fns
  {:avcodec_version {:rettype :int32
                     :doc "Return the version of the avcodec library"}
   :avcodec_configuration {:rettype :string
                           :doc "Return the build configuration of the avcodec lib"}
   :avcodec_license {:rettype :string
                     :doc "Return the license of the avcodec lib"}
   :av_codec_iterate {:rettype :pointer
                      :argtypes [['opaque :pointer]]
                      :doc "Iterate through av code objects.  Returns an AVCodec*"}
   :av_strerror {:rettype :int32
                 :argtypes [['errnum :int32]
                            ['errbuf :pointer]
                            ['errbuf-size :size-t]]
                 :doc "Get a string description for an error."}
   :av_codec_is_encoder {:rettype :int32
                         :argtypes [['codec :pointer]]
                         :doc "Return nonzero if encoder"}
   :av_codec_is_decoder {:rettype :int32
                         :argtypes [['codec :pointer]]
                         :doc "Return nonzero if encoder"}
   :avcodec_find_encoder_by_name {:rettype :pointer
                                  :argtypes [['name :string]]
                                  :doc "Find an encoder by name"}
   :avcodec_find_encoder {:rettype :pointer
                          :argtypes [['id :int32]]
                          :doc "Find an encoder codec id"}
   :avcodec_find_decoder_by_name {:rettype :pointer
                                  :argtypes [['name :string]]
                                  :doc "Find a decoder by name"}
   :avcodec_find_decoder {:rettype :pointer
                          :argtypes [['id :int32]]
                          :doc "Find a decoder codec id"}
   :avcodec_alloc_context3 {:rettype :pointer
                            :argtypes [['codec :pointer?]]
                            :doc "Allocate an avcodec context"}
   :avcodec_free_context {:rettype :void
                          :argtypes [['codec-ptr-ptr :pointer]]
                          :doc "Free the context.  Expects a ptr-ptr to be passed in"}
   :avcodec_open2 {:rettype :int32
                   :argtypes [['c :pointer]
                              ['codec :pointer]
                              ['opts :pointer?]]
                   :check-error? true
                   :doc "Open the codec with the context"}
   :av_packet_alloc {:rettype :pointer
                     :doc "allocate an av packet"}
   :av_packet_free {:rettype :void
                    :argtypes [['packet :pointer]]
                    :doc "free an av packet"}
   :av_frame_alloc {:rettype :pointer
                    :doc "allocate an av frame"}
   :av_frame_free {:rettype :void
                   :argtypes [['frame :pointer]]
                   :doc "free an av frame"}
   :av_frame_get_buffer {:rettype :int32
                         :check-error? true
                         :argtypes [['frame :pointer]
                                    ['align :int32]]
                         :doc "Allocate the frame's data buffer"}
   :av_frame_make_writable {:rettype :int32
                            :check-error? true
                            :argtypes [['frame :pointer]]
                            :doc "Ensure frame is writable"}
   :avcodec_send_frame {:rettype :int32
                        :check-error? true
                        :argtypes [['ctx :pointer]
                                   ['frame :pointer?]]
                        :doc "Send a frame to encode.  A nil frame flushes the buffer"}
   ;;This returns errors during normal course of operation
   :avcodec_receive_packet {:rettype :int32
                            :argtypes [['ctx :pointer]
                                       ['packet :pointer]]
                            :doc "Get an encoded packet.  Packet must be unref-ed"}
   :av_packet_unref {:rettype :int32
                     :check-error? true
                     :argtypes [['packet :pointer]]
                     :doc "Unref a packet after from receive frame"}})


(defonce ^:private lib (dt-ffi/library-singleton #'avcodec-fns))

;;Safe to call on uninitialized library.  If the library is initialized, however,
;;a new library instance is created from the latest avcodec-fns
(dt-ffi/library-singleton-reset! lib)


(defn- find-avcodec-fn
  [fn-kwd]
  (dt-ffi/library-singleton-find-fn lib fn-kwd))


(declare str-error)


(defmacro check-error
  [error-val]
  `(do
     (errors/when-not-errorf
      (>= ~error-val 0)
      "Exception calling avcodec: (%d) - \"%s\""
      ~error-val (if-let [err-name#  (get av-error/value->error-map ~error-val)]
                   err-name#
                   (str-error ~error-val)))
     ~error-val))


(dt-ffi/define-library-functions avclj.avcodec/avcodec-fns find-avcodec-fn check-error)


(defn str-error
  [error-num]
  (let [charbuf (native-buffer/malloc 64 {:resource-type nil})]
    (try
      (let [res (av_strerror error-num charbuf 64)]
        (if (>= (long res) 0)
          (dt-ffi/c->string charbuf)
          (format "Unreconized error num: %s" error-num)))
      (finally
        (native-buffer/free charbuf)))))


(defn initialize!
  []
  (if (nil? (dt-ffi/library-singleton-library lib))
    (do
      (dt-ffi/library-singleton-set! lib "avcodec")
      :ok)
    :already-initialized))


(defn- read-codec-pixfmts
  [addr]
  (let [nbuf (-> (native-buffer/wrap-address addr 4096 nil)
                 (native-buffer/set-native-datatype :int32))]
    (->> (take-while #(not= -1 %) nbuf)
         (mapv av-pixfmt/value->pixfmt))))


(defn expand-codec
  [codec-ptr]
  (when codec-ptr
    (let [codec (dt-ffi/ptr->struct (:datatype-name @av-context/codec-def*) codec-ptr)]
      {:codec codec-ptr
       :name (dt-ffi/c->string (Pointer. (:name codec)))
       :long-name (dt-ffi/c->string (Pointer. (:long-name codec)))
       :media-type (:type codec)
       :pix-fmts (read-codec-pixfmts (:pix-fmts codec))
       :codec-id (:id codec)
       :encoder? (== 1 (long (av_codec_is_encoder codec-ptr)))
       :decoder? (== 1 (long (av_codec_is_decoder codec-ptr)))})))


(defn list-codecs
  "Iterate through codecs returning a list of codec properties"
  []
  ;;ensure struct definition
  (let [opaque (dt-ffi/make-ptr :pointer 0)]
    (->> (repeatedly #(av_codec_iterate opaque))
         (take-while identity)
         (mapv expand-codec))))


(defn find-encoder-by-name
  [name]
  (expand-codec (avcodec_find_encoder_by_name name)))


(defn find-encoder
  [codec-id]
  (expand-codec (avcodec_find_encoder codec-id)))


(defn find-decoder-by-name
  [name]
  (expand-codec (avcodec_find_decoder_by_name name)))


(defn find-decoder
  [codec-id]
  (expand-codec (avcodec_find_decoder codec-id)))


(defn alloc-context
  ^Map []
  (->> (avcodec_alloc_context3 nil)
       (dt-ffi/ptr->struct (:datatype-name @av-context/context-def*))))


(defn free-context
  [ctx]
  (resource/stack-resource-context
   (let [ctx-ptr (->> (dt-ffi/->pointer ctx)
                      (.address)
                      (dt-ffi/make-ptr :pointer))]
     (avcodec_free_context ctx-ptr))))


(defn alloc-packet
  ^Map []
  (->> (av_packet_alloc)
       (dt-ffi/ptr->struct (:datatype-name @av-context/packet-def*))))


(defn free-packet
  [pkt]
  (resource/stack-resource-context
   (let [pkt-ptr (->> (dt-ffi/->pointer pkt)
                      (.address)
                      (dt-ffi/make-ptr :pointer))]
     (av_packet_free pkt-ptr))))


(defn alloc-frame
  ^Map []
  (->> (av_frame_alloc)
       (dt-ffi/ptr->struct (:datatype-name @av-context/frame-def*))))


(defn free-frame
  [pkt]
  (resource/stack-resource-context
   (let [pkt-ptr (->> (dt-ffi/->pointer pkt)
                      (.address)
                      (dt-ffi/make-ptr :pointer))]
     (av_frame_free pkt-ptr))))
