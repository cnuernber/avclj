(ns avclj
  (:require [tech.v3.datatype :as dtype]
            [avclj.ffi :as av-ffi]))


(defn initialize!
  []
  (av-ffi/set-library! "avcodec"))
