(ns avclj.avutil
  (:require [tech.v3.datatype.ffi :as dt-ffi]
            [avclj.avcodec :refer [check-error]]
            [tech.v3.resource :as resource])
  (:import [tech.v3.datatype.ffi Pointer]))


(def avutil-def
  {:av_dict_set {:rettype :int32
                 :argtypes [['pm :pointer]
                            ['key :string]
                            ['value :pointer]
                            ['flags :int32]]
                 :doc "Set a key in the dictionary.  If value is nil then key is removed"}
   :av_dict_count {:rettype :int32
                   :argtypes [['m :pointer]]
                   :doc "Get the count of the dictionary values"}
   :av_dict_free {:rettype :void
                  :argtypes [['pm :pointer]]
                  :doc "Free the dict and associated keys"}
   :av_rescale {:rettype :int64
                :argtypes [['a :int64]
                           ['b :int64]
                           ['c :int64]]
                :doc "/**
 * Rescale a 64-bit integer with rounding to nearest.
 *
 * The operation is mathematically equivalent to `a * b / c`, but writing that
 * directly can overflow.
 *
 * This function is equivalent to av_rescale_rnd() with #AV_ROUND_NEAR_INF.
 *
 * @see av_rescale_rnd(), av_rescale_q(), av_rescale_q_rnd()
 */"}

   })



(defonce lib (dt-ffi/library-singleton #'avutil-def))
(dt-ffi/library-singleton-reset! lib)

(defn initialize!
  []
  (dt-ffi/library-singleton-set! lib "avutil"))

(defn find-fn [fn-name] (dt-ffi/library-singleton-find-fn lib fn-name))

(dt-ffi/define-library-functions avclj.avutil/avutil-def find-fn check-error)


(defn alloc-dict
  []
  (dt-ffi/make-ptr :pointer 0))


(defn free-dict!
  [dict]
  (av_dict_free dict))


(defn set-key-value!
  ([dict key val flags]
   (if val
     (resource/stack-resource-context
      (let [val-ptr (dt-ffi/string->c val {:resource-type :auto})]
        (av_dict_set dict key val-ptr flags)))
     (av_dict_set dict key nil flags)))
  ([dict key val]
   (set-key-value! dict key val 0)))


(defn dict-count
  [dict]
  (av_dict_count (Pointer. (dict 0))))


(def ^{:tag 'long} AV_DICT_MATCH_CASE      1)  ;; /**< Only get an entry with exact-case key match. Only relevant in av_dict_get(). */
(def ^{:tag 'long} AV_DICT_IGNORE_SUFFIX   2)   ;; /**< Return first entry in a dictionary whose first part corresponds to the search key,
                                  ;; ignoring the suffix of the found key string. Only relevant in av_dict_get(). */
(def ^{:tag 'long} AV_DICT_DONT_STRDUP_KEY 4)   ;; /**< Take ownership of a key that's been
                                  ;;      allocated with av_malloc() or another memory allocation function. */
(def ^{:tag 'long} AV_DICT_DONT_STRDUP_VAL 8)   ;; /**< Take ownership of a value that's been
                                  ;;       allocated with av_malloc() or another memory allocation function. */
(def ^{:tag 'long} AV_DICT_DONT_OVERWRITE 16)   ;; ///< Don't overwrite existing entries.
(def ^{:tag 'long} AV_DICT_APPEND         32)   ;; /**< If the entry already exists, append to it.  Note that no
                                  ;;    delimiter is added, the strings are simply concatenated. */
(def ^{:tag 'long} AV_DICT_MULTIKEY       64)   ;; /**< Allow to store several equal keys in the dictionary */
