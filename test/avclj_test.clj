(ns avclj-test
  (:require [clojure.test :refer [deftest is]]
            [avclj :as avclj]
            [tech.v3.tensor :as dtt]
            [tech.v3.datatype :as dtype]
            [tech.v3.libs.buffered-image :as bufimg]))


(defn img-tensor
  [^long offset]
  (dtt/compute-tensor [256 256 3]
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

(defn encode-demo
  []

  )
