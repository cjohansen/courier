(ns courier.client
  (:require #?(:clj [clj-http.client :as http]
               :cljs [cljs-http.client :as http])))

(defn success? [res]
  (http/success? res))

(defmulti request (fn [request] [(:method request) (:url request)]))

(defmethod request :default [request]
  (http/request request))
