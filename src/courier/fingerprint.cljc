(ns courier.fingerprint
  "Create unique string fingerprints for arbitrary data structures"
  (:require [clojure.walk :as walk]
            [clojure.string :as str])
  #?(:clj (:import [java.security MessageDigest])))

(defn sorted
  "Recursively sort maps and sets in a data structure"
  [data]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x) (apply sorted-map (apply concat x))
       (set? x) (apply sorted-set x)
       :else x))
   data))

(defn md5 [#?(:clj ^String s
              :cljs s)]
  #?(:clj
     (let [md (MessageDigest/getInstance "MD5")]
       (.update md (.getBytes s))
       (str/join (map #(format "%x" %) (.digest md))))
     :cljs s))

(defn fingerprint [data]
  (binding [*print-length* -1]
    (md5 (pr-str (sorted data)))))

(comment
  (fingerprint [1 2 3])
  (fingerprint (list 1 2 3))


  )
