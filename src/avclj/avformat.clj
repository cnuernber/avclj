(ns avclj.avformat
  (:require [tech.v3.datatype.ffi :as dt-ffi]
            [avclj.avcodec :refer [check-error]]
            [avclj.av-context :as av-context]
            [tech.v3.resource :as resource])
  (:import [tech.v3.datatype.ffi Pointer]))


(def avformat-def
  {:avformat_version {:rettype :int32
                      :doc "version of the library"}
   :avformat_configuration {:rettype :string
                            :doc "Build configuration of the library"}
   :avformat_license {:rettype :string
                      :doc "License of the library"}
   :avformat_alloc_output_context2 {:rettype :int32
                                    :argtypes [['ctx :pointer]
                                               ['oformat :pointer?]
                                               ;;one or the other of format_name
                                               ;;or file_name may be used.
                                               ['format_name :pointer?]
                                               ['file_name :pointer?]]
                                    :check-error? true
                                    :doc "Open an output context"}
   :avformat_free_context {:rettype :void
                           :argtypes [['ctx :pointer]]
                           :doc "Free an avformat context"}
   :avformat_new_stream {:rettype :pointer
                         :argtypes [['avfmt-ctx :pointer]
                                    ['codec :pointer]]
                         :doc "Create a new stream"}
   :avio_open2 {:rettype :int32
                :argtypes [['ctx :pointer]
                           ['url :string]
                           ['flags :int32]
                           ['int_cp :pointer]
                           ['options :pointer]]
                :doc "Open an io pathway"}
   :avio_close {:rettype :int32
                :argtypes [['s :pointer]]
                :doc "Close the avio context"}
   })


(defonce avformat (dt-ffi/library-singleton #'avformat-def))
(dt-ffi/library-singleton-reset! avformat)


(defn initialize!
  []
  (dt-ffi/library-singleton-set! avformat "avformat"))


(def ^{:tag 'long} AVIO_FLAG_READ  1)
(def ^{:tag 'long} AVIO_FLAG_WRITE 2)
(def ^{:tag 'long} AVIO_FLAG_READ_WRITE 3)

(defn find-fn [fn-name] (dt-ffi/library-singleton-find-fn avformat fn-name))


(dt-ffi/define-library-functions avclj.avformat/avformat-def find-fn check-error)


(defn alloc-output-context
  [output-format]
  (resource/stack-resource-context
   (let [data-ptr (dt-ffi/make-ptr :pointer 0)
         format-ptr (dt-ffi/string->c output-format)]
     (avformat_alloc_output_context2 data-ptr nil format-ptr nil)
     (dt-ffi/ptr->struct (:datatype-name @av-context/av-format-context-def*)
                         (Pointer. (data-ptr 0))))))


(defn new-stream
  [avfmt-ctx codec]
  (->> (avformat_new_stream avfmt-ctx codec)
       (dt-ffi/ptr->struct (:datatype-name @av-context/av-stream-def*))))
