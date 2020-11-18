(ns courier.cache-test
  (:require [courier.cache :as sut]
            [clojure.test :refer [deftest is]]))

(deftest cache-key-bare-request
  (is (= (sut/cache-key {:req {:method :get
                               :url "https://example.com/"}} nil)
         [:courier.http/req {:method :get
                             :url "https://example.com/"}])))

(deftest cache-key-bare-request-with-headers-query-params-and-body
  (is (= (sut/cache-key {:req {:method :post
                               :as :json
                               :content-type :json
                               :headers {"X-Correlation-Id" 42}
                               :form-params {:grant_type "client_credentials"}
                               :basic-auth ["id" "secret"]
                               :query-params {:client "someone"}
                               :url "https://example.com/"}} nil)
         [:courier.http/req
          {:method :post
           :url "https://example.com/"
           :as :json
           :content-type :json
           :query-params {:client "someone"}
           :headers {"x-correlation-id" 42}
           :hash "f99b15885a9cf25b45b1472abec32b60"}])))

(deftest cache-key-bare-request-with-params
  (is (= (sut/cache-key {:req {:method :get
                               :url "https://example.com/"}} {:id 42})
         [:courier.http/req
          {:method :get
           :url "https://example.com/"
           :lookup-params {:id 42}}])))

(deftest inline-req-fn
  (is (->> (sut/cache-key {:req-fn (fn [params])} nil)
           first
           name
           (re-find #"fn"))))

(defn stuff-request [params])

(deftest defn-req-fn
  (is (= (sut/cache-key {:req-fn stuff-request} {:id 42})
         [:courier.cache-test/stuff-request {:id 42}])))

(deftest var-req-fn
  (is (= (sut/cache-key {:req-fn #'stuff-request} {:id 42})
         [:courier.cache-test/stuff-request {:id 42}])))

(deftest req-fn-with-meta
  (is (= (sut/cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})} {:id 42})
         [:lol {:id 42}]))
  (is (= (sut/cache-key {:req-fn (with-meta (fn [_])
                                   {:name "lol"
                                    :ns "ok"})} {:id 42})
         [:ok/lol {:id 42}])))
