(ns avclj.av-codec-id-defs)


(defmacro define-codec-constants
  [codec-ids]
  `(do
     (def codec-ids ~codec-ids)
     ~@(->> codec-ids
            (map (fn [[k v]]
                   (let [sym (symbol k)]
                     `(def ~sym ~v)))))))
