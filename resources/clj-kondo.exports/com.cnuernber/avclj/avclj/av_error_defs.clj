(ns avclj.av-error-defs)


(defmacro define-error-constants
  [error-constants]
  `(do
     (def error->value-map ~error-constants)
     ~@(->> error-constants
            (map (fn [[k v]]
                   (let [sym (symbol k)]
                     `(def ~sym ~v)))))))
