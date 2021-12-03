(ns avclj.av-pixfmt-data)


(defmacro ^:private define-pixfmt-constants
  [pixfmts]
  `(do
     (def pixfmt-name-value-map ~pixfmts)
     ~@(->> pixfmts
            (map (fn [[k v]]
                   (let [sym (symbol k)]
                     `(def ~sym ~v)))))))
