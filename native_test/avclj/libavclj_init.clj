(ns avclj.libavclj-init
  (:require [avclj.avcodec :as avcodec]
            [avclj.avformat :as avformat]
            [avclj.avutil :as avutil]
            [avclj.swscale :as swscale])
  (:import [java.util.concurrent ConcurrentHashMap]))


(def ^{:tag ConcurrentHashMap} encoders (ConcurrentHashMap.))


(def initialized?* (atom false))


(defn initialize-avclj
  []
  ;;ensure to reference the encoders
  (if (first (swap-vals!
              initialized?*
              (fn [init]
                (when-not init
                  (avcodec/set-library-instance! (avclj.avcodec.Bindings.))
                  (avformat/set-library-instance! (avclj.avformat.Bindings.))
                  (avutil/set-library-instance! (avclj.avutil.Bindings.))
                  (swscale/set-library-instance! (avclj.swscale.Bindings.))))))
    1
    0))


(defn is-avclj-initialized
  []
  (if @initialized?* 1 0))
