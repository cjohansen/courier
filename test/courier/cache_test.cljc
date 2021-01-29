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

(deftest cache-key--lookup-id-and-params
  (is (= (sut/cache-key
          {:req-fn identity
           :lookup-id :get-stuff}
          {:id 42
           :name "Someone"})
         [:get-stuff {:id 42 :name "Someone"}])))

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

(deftest cache-key--custom-cache-key-data
  (is (= (sut/cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})
                         :cache-key {:custom "Key"}}
                        {:id 42})
         {:custom "Key"})))

(defmethod sut/cache-key ::fully-custom [spec params]
  "/lol/sir")

(deftest cache-key--custom-implementation
  (is (= (sut/cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})
                         :lookup-id ::fully-custom}
                        {:id 42})
         "/lol/sir")))

(deftest cache-key-filename--inline-request
  (is (= (sut/filename "/tmp" {:req {:method :get
                                     :url "http://localhost"}} {})
         "/tmp/courier.http.req/8e/36e38490976155a114f40db433e4.edn")))

(deftest cache-key-filename--lookup-id-params
  (is (= (sut/filename "/tmp"
                       {:req-fn identity
                        :lookup-id :services/thing}
                       {:id 42
                        :sub-category "luls"})
         "/tmp/services.thing/fd/2c9f221bf45ca8977949aed85c3d0.edn")))

(deftest cache-key-filename--function-name-params
  (is (= (sut/filename "/tmp"
                       {:req-fn (with-meta (fn [_]) {:name "lol"})}
                       {:id 42})
         "/tmp/lol/d4/db8d433b3e2cee1cf01b712b1f267.edn")))

(deftest cache-key-filename--custom-cache-key-data
  (is (= (sut/filename "/tmp"
                       {:req-fn (with-meta (fn [_]) {:name "lol"})
                        :cache-key "this/file.edn"}
                       {:id 42})
         "/tmp/this/file.edn")))

(deftest cache-key-filename--custom-cache-key-data-keyword
  (is (= (sut/filename "/tmp"
                       {:req-fn (with-meta (fn [_]) {:name "lol"})
                        :cache-key ::key}
                       {:id 42})
         "/tmp/co/urier.cache-test.key.edn")))

(deftest cache-key-filename--custom-cache-key-data-vector
  (is (= (sut/filename "/tmp"
                       {:req-fn (with-meta (fn [_]) {:name "lol"})
                        :cache-key [42 ::key {:ok true}]}
                       {:id 42})
         "/tmp/42/courier.cache-test.key/31/7e30b8f636bcd5e230d22fea23bb.edn")))

(deftest cache-key-filename--custom-implementation
  (is (= (sut/filename "/tmp"
                       {:req-fn (with-meta (fn [_]) {:name "lol"})
                        :lookup-id ::fully-custom}
                       {:id 42})
         "/tmp/lol/sir")))

(deftest redis-cache-key--inline-request
  (is (= (sut/redis-cache-key {:req {:method :get
                                     :url "http://localhost"}} {})
         "courier.http/req/8e36e38490976155a114f40db433e4")))

(deftest redis-cache-key--lookup-id-params
  (is (= (sut/redis-cache-key {:req-fn identity
                               :lookup-id :services/thing}
                              {:id 42
                               :sub-category "luls"})
         "services/thing/fd2c9f221bf45ca8977949aed85c3d0")))

(deftest redis-cache-key--function-name-params
  (is (= (sut/redis-cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})}
                              {:id 42})
         "lol/d4db8d433b3e2cee1cf01b712b1f267")))

(deftest redis-cache-key--custom-cache-key-data
  (is (= (sut/redis-cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})
                               :cache-key "put/it/in/redis"}
                              {:id 42})
         "put/it/in/redis")))

(deftest redis-cache-key--custom-cache-key-data-keyword
  (is (= (sut/redis-cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})
                               :cache-key ::key}
                              {:id 42})
         "courier.cache-test/key")))

(deftest redis-cache-key--custom-cache-key-data-vector
  (is (= (sut/redis-cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})
                               :cache-key [42 ::key {:ok true}]}
                              {:id 42})
         "42/courier.cache-test/key/317e30b8f636bcd5e230d22fea23bb")))

(deftest redis-cache-key--custom-implementation
  (is (= (sut/redis-cache-key {:req-fn (with-meta (fn [_]) {:name "lol"})
                               :lookup-id ::fully-custom}
                              {:id 42})
         "/lol/sir")))
