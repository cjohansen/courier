(ns courier.client
  (:require #?(:clj [clj-http.client :as http]
               :cljs [cljs-http.client :as http])))

(defn success? [res]
  (http/success? res))

(defmulti request (fn [req] [(:method req) (:url req)]))

(defmethod request :default [req]
  (http/request req))
