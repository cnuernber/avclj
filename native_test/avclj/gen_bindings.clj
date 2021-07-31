(ns avclj.gen-bindings
  "Run this namespace first."
  (:require [avclj.avcodec :as avcodec]
            [avclj.avformat :as avformat]
            [avclj.avutil :as avutil]
            [avclj.swscale :as swscale]
            [tech.v3.datatype.ffi.graalvm :as graalvm]))

(with-bindings {#'*compile-path* "generated_classes"}
  (def avcodec-def (graalvm/define-library
                     avcodec/avcodec-fns
                     nil
                     {:header-files ["<libavcodec/avcodec.h>"]
                      :libraries ["avcodec" "x264"]
                      :classname 'avclj.avcodec.Bindings}))

  (def avformat-def (graalvm/define-library
                      avformat/avformat-def
                      nil
                      {:header-files ["<libavformat/avformat.h>"]
                       :libraries ["avformat"]
                       :classname 'avclj.avformat.Bindings}))

  (def avutil-def (graalvm/define-library
                    avutil/avutil-def
                    nil
                    {:header-files ["<libavutil/avutil.h>"]
                     :libraries ["avutil"]
                     :classname 'avclj.avutil.Bindings}))

  (def swscale-def (graalvm/define-library
                     swscale/swscale-def
                     nil
                     {:header-files ["<libswscale/swscale.h>"]
                      :libraries ["swscale"]
                      :classname 'avclj.swscale.Bindings})))

(require '[avclj.libavclj :as libavclj])

(with-bindings {#'*compile-path* "generated_classes"}
  (graalvm/expose-clojure-functions
   ;;name conflict - initialize is too general
   {#'libavclj/initialize-avclj {:rettype :int64}
    #'libavclj/is-avclj-initialized {:rettype :int64}
    #'libavclj/make-h264-encoder {:rettype :int64
                                  :argtypes [['height :int64]
                                             ['width :int64]
                                             ['out-fname :pointer]
                                             ['input-pixfmt :pointer]]}
    #'libavclj/encode-frame {:rettype :int64
                             :argtypes [['encoder :int64]
                                        ['frame-data :pointer]
                                        ['frame-data-len :int64]]}
    #'libavclj/close-encoder {:rettype :int64
                              :argtypes [['encoder :int64]]}
    #'libavclj/make-decoder {:rettype :int64
                             :argtypes [['fname :pointer]
                                        ['ioWidth :pointer] ;;pointer to int32
                                        ['ioHeight :pointer] ;;pointer to int32
                                        ]}
    #'libavclj/decode-frame {:rettype :int64
                             :argtypes [['decoder :int64]
                                        ['buffer :pointer]]}
    #'libavclj/close-decoder {:rettype :int64
                              :argtypes [['decoder :int64]]}}
   'avclj.LibAVClj nil))
