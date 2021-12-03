(ns avclj.swscale)


(defmacro define-constants
  [constant-data]
  `(do
     (def constants ~constant-data)
     ~@(->> constant-data
            (map (fn [[k v]]
                   (let [sym (symbol k)]
                     `(def ~sym ~v)))))))
