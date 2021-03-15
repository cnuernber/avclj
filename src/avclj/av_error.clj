(ns avclj.av-error
  (:require [clojure.set :as set]))


(def error->value-map
  {
   "AVERROR_BSF_NOT_FOUND" -1179861752
   "AVERROR_BUG" -558323010
   "AVERROR_BUFFER_TOO_SMALL" -1397118274
   "AVERROR_DECODER_NOT_FOUND" -1128613112
   "AVERROR_DEMUXER_NOT_FOUND" -1296385272
   "AVERROR_ENCODER_NOT_FOUND" -1129203192
   "AVERROR_EOF" -541478725
   "AVERROR_EXIT" -1414092869
   "AVERROR_EXTERNAL" -542398533
   "AVERROR_FILTER_NOT_FOUND" -1279870712
   "AVERROR_INVALIDDATA" -1094995529
   "AVERROR_MUXER_NOT_FOUND" -1481985528
   "AVERROR_OPTION_NOT_FOUND" -1414549496
   "AVERROR_PATCHWELCOME" -1163346256
   "AVERROR_PROTOCOL_NOT_FOUND" -1330794744
   "AVERROR_STREAM_NOT_FOUND" -1381258232
   "AVERROR_BUG2" -541545794
   "AVERROR_UNKNOWN" -1313558101
   "AVERROR_EXPERIMENTAL" -733130664
   "AVERROR_INPUT_CHANGED" -1668179713
   "AVERROR_OUTPUT_CHANGED" -1668179714
   "AVERROR_HTTP_BAD_REQUEST" -808465656
   "AVERROR_HTTP_UNAUTHORIZED" -825242872
   "AVERROR_HTTP_FORBIDDEN" -858797304
   "AVERROR_HTTP_NOT_FOUND" -875574520
   "AVERROR_HTTP_OTHER_4XX" -1482175736
   "AVERROR_HTTP_SERVER_ERROR" -1482175992
   "AVERROR_EAGAIN" -11
   })

(def value->error-map (set/map-invert error->value-map))


(defn error-value->name
  [err-val]
  (if-let [retval (get value->error-map (long err-val))]
    retval
    (format "Unrecognized AV error: %d" (long err-val))))

(defn error-name->value
  ^long [err-name]
  (if-let [retval (get error->value-map (long err-name))]
    retval
    (format "Unrecognized AV error: %s" err-name)))


(defmacro define-error-constants
  []
  `(do
     ~@(->> error->value-map
            (map (fn [[k v]]
                   (let [sym (with-meta (symbol k)
                               {:tag ''long})]
                     `(def ~sym ~v)))))))


(define-error-constants)
