(ns avclj-test
  (:require [clojure.test :refer [deftest is]]
            [avclj :as avclj]
            [avclj.av-codec-ids :as codec-ids]
            [tech.v3.tensor :as dtt]
            [tech.v3.datatype :as dtype]
            [tech.v3.libs.buffered-image :as bufimg]))


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


(defn save-tensor
  [tens fname]
  (let [[h w c] (dtype/shape tens)
        bufimg (bufimg/new-image h w :byte-bgr)]
    (-> (dtype/copy! tens bufimg)
        (bufimg/save! fname))))

(avclj/initialize!)


(deftest encode-demo
  (let [encoder-name codec-ids/AV_CODEC_ID_H264
        output-fname "test/data/test-video.mp4"]
    (.delete (java.io.File. output-fname))
    (with-open [encoder (avclj/make-video-encoder
                         256 256 output-fname
                         {:encoder-name encoder-name
                          :bit-rate 600000})]
      (dotimes [iter 600]
        (avclj/encode-frame! encoder (img-tensor [256 256 3] iter))))
    (is (.exists (java.io.File. output-fname)))))
