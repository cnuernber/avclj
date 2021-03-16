(ns skija-movie
  (:require [avclj :as avclj]
            [avclj.av-codec-ids :as codec-ids]
            [tech.v3.datatype :as dtype]
            [tech.v3.io :as io]
            [tech.v3.resource :as resource])
  (:import [org.jetbrains.skija Image ImageInfo Surface Canvas
            ColorType ColorAlphaType ColorSpace Paint PaintStrokeCap
            EncodedImageFormat]))

(set! *warn-on-reflection* true)

(avclj/initialize!)


(defn native-buffer-backed-surface
  "Create a native-buffer packed surface that corresponds to an AV_PIX_FMT_RGBA pixel
  format.

  Returns: `{:backing-store :surface}`"
  [^long height ^long width]
  (let [n-channels 4
        n-bytes (* height width n-channels)
        linesize (* width n-channels)
        nbuf (-> (dtype/make-container :native-heap :uint8 n-bytes)
                 (dtype/as-native-buffer))
        nbuf-adder (.address nbuf)
        surface (Surface/makeRasterDirect
                 (ImageInfo. width height ColorType/RGBA_8888 ColorAlphaType/PREMUL)
                 nbuf-adder
                 linesize)]
    {:backing-store nbuf
     :surface surface}))


(defn draw-circle
  [^Surface surface ^long frame-idx]
  (let [canvas (.getCanvas surface)
        fg-paint (-> (Paint.)
                     (.setARGB 255 248 248 248)
                     (.setStrokeWidth 3)
                     (.setStrokeCap PaintStrokeCap/ROUND))
        pos (rem frame-idx 64)]
    (.drawCircle canvas (+ 32 pos) (+ 16 pos) 16 fg-paint)))


(defn save-surface!
  [^Surface surface path]
  (io/make-parents path)
  (io/copy (-> (.makeImageSnapshot surface)
               (.encodeToData EncodedImageFormat/PNG)
               (.getBytes))
           (io/file path)))


(defn demo-skija-movie
  []
  (resource/stack-resource-context
   (let [{:keys [backing-store surface]} (native-buffer-backed-surface 256 256)
         canvas (.getCanvas ^Surface surface)]
     (with-open [encoder (avclj/make-video-encoder
                          256 256 "skijavid.mp4"
                          {:encoder-name codec-ids/AV_CODEC_ID_H264
                           :input-pixfmt "AV_PIX_FMT_RGBA"
                           :framerate 60})]
       ;;5 second vid
       (dotimes [iter 300]
         (.clear canvas 0x000000FF)
         (draw-circle surface iter)
         (avclj/encode-frame! encoder backing-store))))))
