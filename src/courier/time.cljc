(ns courier.time
  #?(:clj (:import java.time.Instant)))

(defn now []
  #?(:clj (java.time.Instant/now)
     :cljs (.getTime (js/Date.))))

(defn ->inst [i]
  (if (number? i)
    #?(:clj (java.time.Instant/ofEpochMilli i)
       :cljs (js/Date. i))
    i))

(defn before? [a b]
  #?(:clj (.isBefore (->inst a) (->inst b))
     :cljs (< a b)))

(defn millis [t]
  (if (number? t)
    t
    #?(:clj (.toEpochMilli t)
       :cljs (.getTime t))))

(defn add-millis [t ms]
  #?(:clj (millis (.plusMillis t ms))
     :cljs (+ t ms)))
