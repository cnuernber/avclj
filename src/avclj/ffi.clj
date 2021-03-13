(ns avclj.ffi
  (:require [tech.v3.datatype :as dtype]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.struct :as dt-struct]
            [tech.v3.datatype.ffi.size-t :as ffi-size-t]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.resource :as resource]
            [clojure.tools.logging :as log])
  (:import [tech.v3.datatype.ffi Pointer]))


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
                   :doc "Open the codec with the context"}
   :av_packet_alloc {:rettype :pointer}

   })



(def ^:private lib-def* (delay (dt-ffi/define-library avcodec-fns)))

(defonce ^:private library* (atom nil))
(defonce ^:private library-path* (atom nil))


(defn reset-library!
  []
  (when @library-path*
    (reset! library* (dt-ffi/instantiate-library @lib-def*
                                                 (:libpath @library-path*)))))


(reset-library!)


(defn set-library!
  [libpath]
  (when @library*
    (log/warnf "Python library is being reinitialized to (%s).  Is this what you want?"
               libpath))
  (reset! library-path* {:libpath libpath})
  (reset-library!))

(defn- find-avcodec-fn
  [fn-kwd]
  (let [avcodec @library*]
    (errors/when-not-error
     avcodec
     "Library not found.  Has set-library! been called?")
    (if-let [retval (fn-kwd @avcodec)]
      retval
      (errors/throwf "Python function %s not found" (symbol (name fn-kwd))))))


(defmacro define-avcodec-functions
  []
  `(do
     ~@(->>
        avcodec-fns
        (map
         (fn [[fn-name {:keys [rettype argtypes] :as fn-data}]]
           (let [fn-symbol (symbol (name fn-name))
                 requires-resctx? (first (filter #(= :string %)
                                                 (concat (map second argtypes)
                                                         [rettype])))]
             `(defn ~fn-symbol
                ~(:doc fn-data "No documentation!")
                ~(mapv first argtypes)
                (let [~'ifn (find-avcodec-fn ~fn-name)]
                  (do
                    ~(if requires-resctx?
                       `(resource/stack-resource-context
                         (let [~'retval
                               (~'ifn ~@(map
                                         (fn [[argname argtype]]
                                           (cond
                                             (#{:int8 :int16 :int32 :int64} argtype)
                                             `(long ~argname)
                                             (#{:float32 :float64} argtype)
                                             `(double ~argname)
                                             (= :string argtype)
                                             `(dt-ffi/string->c ~argname)
                                             :else
                                             argname))
                                         argtypes))]
                           ~(if (= :string rettype)
                              `(dt-ffi/c->string ~'retval)
                              `~'retval)))
                       `(~'ifn ~@(map (fn [[argname argtype]]
                                        (cond
                                          (#{:int8 :int16 :int32 :int64} argtype)
                                          `(long ~argname)
                                          (#{:float32 :float64} argtype)
                                          `(double ~argname)
                                          (= :string argtype)
                                          `(dt-ffi/string->c ~argname)
                                          :else
                                          argname))
                                      argtypes))))))))))))

(define-avcodec-functions)


(defn initialize!
  []
  (if (nil? @library*)
    (do
      (set-library! "avcodec")
      :ok)
    :already-initialized!))


(def codec-structdef*
  (delay (dt-struct/define-datatype!
           :avcodec [{:name :name
                      :datatype (ffi-size-t/ptr-t-type)}
                     {:name :long-name
                      :datatype (ffi-size-t/ptr-t-type)}
                     {:name :media-type
                      :datatype :int32}
                     {:name :codec-id
                      :datatype :int32}])))


(defn ptr->struct
  [struct-type ptr-type]
  (let [n-bytes (:datatype-size (dt-struct/get-struct-def struct-type))
        src-ptr (dt-ffi/->pointer ptr-type)
        nbuf (native-buffer/wrap-address (.address src-ptr)
                                         n-bytes
                                         src-ptr)]
    (dt-struct/inplace-new-struct struct-type nbuf)))


(defn expand-codec
  [codec-ptr]
  @codec-structdef*
  (when codec-ptr
    (let [codec (ptr->struct :avcodec codec-ptr)]
      {:codec codec-ptr
       :name (dt-ffi/c->string (Pointer. (:name codec)))
       :long-name (dt-ffi/c->string (Pointer. (:long-name codec)))
       :media-type (:media-type codec)
       :codec-id (:codec-id codec)
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


(def context-structdef*
  (delay
    (do
      (dt-struct/define-datatype!
        :avrational
        [{:name :num :datatype :int32}
         {:name :den :datatype :int32}])
      (dt-struct/define-datatype!
        :avcodec
        [{:name :av-class :datatype (ffi-size-t/ptr-t-type)}
         {:name :log-level-offset :datatype :int32}
         {:name :codec-type :datatype :int32}
         {:name :codec :datatype (ffi-size-t/ptr-t-type)}
         {:name :codec-id :datatype :int32}
         {:name :codec-tag :datatype :uint32}
         {:name :priv-data :datatype (ffi-size-t/ptr-t-type)}
         {:name :internal :datatype (ffi-size-t/ptr-t-type)}
         {:name :opaque :datatype (ffi-size-t/ptr-t-type)}
         {:name :bit-rate :datatype :int64}
         {:name :bit-rate-tolerance :datatype :int32}
         {:name :global-quality :datatype :int32}
         {:name :compression-level :datatype :int32}
         {:name :flags :datatype :int32}
         {:name :flags2 :datatype :int32}
         {:name :extradata :datatype (ffi-size-t/ptr-t-type)}
         {:name :extradata-size :datatype :int32}
         {:name :time-base :datatype :avrational}
         {:name :ticks-per-frame :datatype :int32}
         {:name :delay :datatype :int32}
         {:name :width :datatype :int32}
         {:name :height :datatype :int32}
         {:name :coded-width :datatype :int32}
         {:name :coded-height :datatype :int32}
         {:name :gop-size :datatype :int32}
         {:name :pix-fmt :datatype :int32}]))))
