(ns avclj.main
  (:require [avclj :as avclj]
            [avclj.avcodec :as avcodec]
            [avclj.avformat :as avformat]
            [avclj.avutil :as avutil]
            [avclj.swscale :as swscale]
            [avclj.av-codec-ids :as codec-ids]
            ;;Must be included for graal runtime support
            [tech.v3.datatype.ffi.graalvm-runtime]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.tensor :as dtt])
  (:gen-class))


(comment
  (do
    (require '[tech.v3.datatype.ffi.graalvm :as graalvm])
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
                           :classname 'avclj.swscale.Bindings}))
      )

    )
  )

(defn img-tensor
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


(defn -main
  [& arglist]
  (avcodec/set-library-instance! (avclj.avcodec.Bindings.))
  (avformat/set-library-instance! (avclj.avformat.Bindings.))
  (avutil/set-library-instance! (avclj.avutil.Bindings.))
  (swscale/set-library-instance! (avclj.swscale.Bindings.))
  ;;No need for initialize, that is done already!!
  (let [encoder-name codec-ids/AV_CODEC_ID_H264
        output-fname "graal-native-video.mp4"]
    (.delete (java.io.File. output-fname))
    (with-open [encoder (avclj/make-video-encoder
                         256 256 output-fname
                         {:encoder-name encoder-name
                          :bit-rate 600000})]
      (dotimes [iter 600]
        (avclj/encode-frame! encoder (img-tensor [256 256 3] iter)))))
  )
