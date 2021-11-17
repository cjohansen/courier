(ns courier.time
  #?(:clj (:import (java.time Instant))))

(defn now #?(:clj ^Instant []
             :cljs [])
  #?(:clj (Instant/now)
     :cljs (.getTime (js/Date.))))

(defn ->inst #?(:clj ^Instant [i]
                :cljs [i])
  (if (number? i)
    #?(:clj (Instant/ofEpochMilli i)
       :cljs (js/Date. i))
    i))

(defn before? [a b]
  #?(:clj (.isBefore (->inst a) (->inst b))
     :cljs (< a b)))

(defn millis [t]
  (if (number? t)
    t
    #?(:clj (.toEpochMilli ^Instant t)
       :cljs (.getTime t))))

(defn add-millis [t ms]
  #?(:clj (millis (.plusMillis ^Instant t ms))
     :cljs (+ t ms)))
