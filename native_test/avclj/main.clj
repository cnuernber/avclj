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
            [tech.v3.tensor :as dtt]
            [avclj.libavclj-init :as libavclj-init])
  (:gen-class))



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
  (libavclj-init/initialize-avclj)
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
