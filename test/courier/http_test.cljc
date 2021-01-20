(ns courier.http-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is]]
            [courier.cache :as cache]
            [courier.client :as client]
            [courier.http :as sut]
            [courier.time :as time]))

;; Helper tooling

(def responses (atom {}))

(defmethod client/request :default [req]
  (let [k [(:method req) (:url req)]
        res (or (first (get @responses k))
                {:status 200
                 :body {:request req}})]
    (swap! responses update k rest)
    res))

(defmacro with-responses [rs & body]
  `(do
     (reset! responses ~rs)
     (let [res# ~@body]
       (reset! responses {})
       res#)))

(defn time-events!! [ch]
  (a/<!!
   (a/go-loop [res []]
     (let [start (time/millis (time/now))]
       (if-let [event (a/<! ch)]
         (recur (conj res {:event (:event event)
                           :elapsed (- (time/millis (time/now)) start)}))
         res)))))

(defn summarize-req [{:keys [method url headers body]}]
  (into [method url] (remove nil? [headers body])))

(defn summarize-res [{:keys [status headers body]}]
  (into [status] (remove nil? [headers body])))

(defn summarize-event [event]
  (into (vec (remove nil? ((juxt :event :path) event)))
        (cond
          (:res event) [(summarize-res (:res event))]
          (:courier.error/reason event) [(:courier.error/reason event)
                                         (:courier.error/data event)]
          (:throwable event) [(-> event :throwable .getMessage) (:source event)]
          :default [(summarize-req (:req event))])))

;; Unit tests

(deftest lookup-params-uses-all-params-by-defaut
  (is (= (sut/lookup-params {:params [:token :id]}
                            {:token "ejY..."
                             :id 42})
         {:required [:token :id]
          :params {:token "ejY..."
                   :id 42}})))

(deftest lookup-params-uses-only-lookup-params
  (is (= (sut/lookup-params {:params [:token :id]
                             :lookup-params [:id]}
                            {:token "ejY..."
                             :id 42})
         {:required [:id]
          :params {:id 42}})))

(deftest lookup-params-with-surgical-selections
  (is (= (sut/lookup-params {:params [:token :config :id]
                             :lookup-params [[:config :host] :id]}
                            {:token "ejY..."
                             :id 42
                             :config {:host "example.com"
                                      :client-id "..."
                                      :client-secret "..."}})
         {:required [:config :id]
          :params {:id 42
                   :config {:host "example.com"}}})))

(deftest lookup-params-with-no-params
  (is (= (sut/lookup-params {}
                            {:token "ejY..."
                             :id 42
                             :config {:host "example.com"
                                      :client-id "..."
                                      :client-secret "..."}})
         {:required []
          :params nil})))

;; make-requests tests

(deftest emits-start-and-end-events
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 200
                            :body {:yep "Indeed"}}]}
           (->> {:example {:req {:url "http://example.com/"}}}
                (sut/make-requests {})
                sut/collect!!))
         [{:event ::sut/request
           :path :example
           :req {:method :get
                 :throw-exceptions false
                 :url "http://example.com/"}}
          {:event ::sut/response
           :path :example
           :req {:method :get
                 :throw-exceptions false
                 :url "http://example.com/"}
           :res {:status 200
                 :body {:yep "Indeed"}}
           :success? true}])))

(deftest determines-failure-with-custom-fn
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 200
                                 :body {:yep "Indeed"}}]}
                (->> {:example {:req {:url "http://example.com/"}
                                :success? #(= 201 (-> % :res :status))}}
                     (sut/make-requests {})
                     sut/collect!!
                     second
                     :success?)))))

(deftest determines-success-with-custom-fn
  (is (true? (with-responses {[:get "http://example.com/"]
                              [{:status 301
                                :body {:yep "Indeed"}}]}
               (->> {:example {:req {:url "http://example.com/"}
                               :success? #(= 301 (-> % :res :status))}}
                    (sut/make-requests {})
                    sut/collect!!
                    second
                    :success?)))))

(deftest is-failure-when-success-fn-throws
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 201
                                 :body {:yep "Indeed"}}]}
                (->> {:example {:req {:url "http://example.com/"}
                                :success? (fn [_] (throw (ex-info "Oops!" {})))}}
                     (sut/make-requests {})
                     sut/collect!!
                     (drop 2)
                     first
                     :success?)))))

(deftest emits-retry-event
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 500}
                           {:status 200 :body {:ok? true}}]}
           (->> {:example {:req {:url "http://example.com/"}
                           :retry-fn (sut/retry-fn {:retries 2})}}
                (sut/make-requests {})
                sut/collect!!
                (map summarize-event)))
         [[::sut/request  :example [:get "http://example.com/"]]
          [::sut/response :example [500]]
          [::sut/request  :example [:get "http://example.com/"]]
          [::sut/response :example [200 {:ok? true}]]])))

(deftest retries-with-delays
  (is (<= 55
          (with-responses {[:get "http://example.com/"]
                           [{:status 500}
                            {:status 500}
                            {:status 500}
                            {:status 500}
                            {:status 200 :body {:ok? true}}]}
            (->> {:example {:req {:url "http://example.com/"}
                            :retry-fn (sut/retry-fn {:retries 4
                                                     :delays [5 10 20]})}}
                 (sut/make-requests {})
                 time-events!!
                 (map :elapsed)
                 (reduce + 0)))
          79)))

(deftest bails-after-the-specified-number-of-retries
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 500}
                           {:status 500}
                           {:status 500}
                           {:status 500}
                           {:status 200 :body {:ok? true}}]}
           (->> {:example {:req {:url "http://example.com/"}
                           :retry-fn (sut/retry-fn {:retries 1
                                                    :delays [5 10 20]})}}
                (sut/make-requests {})
                sut/collect!!
                (map summarize-event)))
         [[::sut/request :example [:get "http://example.com/"]]
          [::sut/response :example [500]]
          [::sut/request :example [:get "http://example.com/"]]
          [::sut/response :example [500]]
          [::sut/failed :example :courier.error/retries-exhausted {:max-retries 1
                                                                   :attempts 2}]])))

(deftest passes-named-parameters-to-req-fn
  (is (= (->> {:example {:req-fn (fn [params]
                                   {:url "http://example.com/"
                                    :params params})
                         :params [:token]}}
              (sut/make-requests
               {:params
                {:token "ejY-secret-..."
                 :other "Stuff"}})
              sut/collect!!
              second
              :res
              :body)
         {:request
          {:url "http://example.com/"
           :throw-exceptions false
           :params {:token "ejY-secret-..."}
           :method :get}})))

(deftest cannot-make-request-without-required-data
  (is (= (->> {:example {:req-fn (fn [params]
                                   {:url "http://example.com/"
                                    :params params})
                         :params [:token :spoken]}}
              (sut/make-requests
               {:params
                {:token "ejY-secret-..."
                 :other "Stuff"}})
              sut/collect!!)
         [{:event ::sut/failed
           :path :example
           :courier.error/reason :courier.error/missing-params
           :courier.error/data [:spoken]}])))

(deftest makes-dependent-request-first
  (is (= (with-responses {[:post "http://example.com/security/"]
                          [{:status 200 :body {:token "ejY..."}}]}
           (->> {:example {:req-fn (fn [{:keys [token]}]
                                     {:url "http://example.com/"
                                      :headers {"Authorization" (str "Bearer " token)}})
                           :params [:token]}}
                (sut/make-requests
                 {:params
                  {:token {::sut/req {:req {:method :post
                                            :url "http://example.com/security/"}}
                           ::sut/select (comp :token :body)}
                   :other "Stuff"}})
                sut/collect!!
                last
                :res))
         {:status 200
          :body {:request
                 {:url "http://example.com/"
                  :throw-exceptions false
                  :headers {"Authorization" "Bearer ejY..."}
                  :method :get}}})))

(deftest makes-multiple-dependent-requests
  (is (= (with-responses {[:post "http://example.com/security/"]
                          [{:status 200 :body {:clue "ejY"}}]

                          [:get "http://example.com/super-security/ejY"]
                          [{:status 200 :body {:token "111"}}]}
           (->> {:example {:req-fn (fn [{:keys [token]}]
                                     {:url "http://example.com/"
                                      :headers {"Authorization" (str "Bearer " token)}})
                           :params [:token]}}
                (sut/make-requests
                 {:params
                  {:clue {::sut/req {:req {:method :post
                                           :url "http://example.com/security/"}}
                          ::sut/select (comp :clue :body)}
                   :token {::sut/req {:req-fn (fn [{:keys [clue]}]
                                                {:method :get
                                                 :url (str "http://example.com/super-security/" clue)})
                                      :params [:clue]}
                           ::sut/select (comp :token :body)}
                   :other "Stuff"}})
                sut/collect!!
                last
                :res))
         {:status 200
          :body {:request
                 {:url "http://example.com/"
                  :throw-exceptions false
                  :headers {"Authorization" "Bearer 111"}
                  :method :get}}})))

(deftest loads-result-from-cache
  (is (= (let [cache (atom {[::sut/req {:method :get
                                        :url "http://example.com/"}]
                            {:req {:method :get
                                   :url "http://example.com"}
                             :res {:status 200
                                   :body "Oh yeah!"}}})]
           (->> {:example {:req {:url "http://example.com/"}}}
                (sut/make-requests {:cache (cache/create-atom-map-cache cache)})
                sut/collect!!))
         [{:req {:method :get
                 :url "http://example.com"}
           :res {:status 200
                 :body "Oh yeah!"}
           :path :example
           :success? true
           :event ::sut/cache-hit}])))

(deftest does-not-load-expired-result-from-cache
  (is (= (let [cache (atom {[::sut/req {:method :get
                                        :url "http://example.com/"}]
                            {:req {:method :get
                                   :url "http://example.com"}
                             :res {:status 200
                                   :body "Oh yeah!"}
                             :expires-at (time/add-millis (time/now) -10)}})]
           (->> {:example {:req {:url "http://example.com/"}}}
                (sut/make-requests {:cache (cache/create-atom-map-cache cache)})
                sut/collect!!
                (map summarize-event)))
         [[::sut/request :example [:get "http://example.com/"]]
          [::sut/response :example [200 {:request {:url "http://example.com/"
                                                   :throw-exceptions false
                                                   :method :get}}]]])))

(deftest uses-cached-dependent-request
  (is (= (let [cache (atom {[::sut/req {:method :post
                                        :url "http://example.com/security/"}]
                            {:req {:method :post
                                   :url "http://example.com/security/"}
                             :res {:status 200
                                   :body {:token "T0k3n"}}}})]
           (->> {:example {:lookup-id :example
                           :req-fn (fn [{:keys [id token]}]
                                     {:url (str "http://example.com/" id)
                                      :headers {"Authorization" (str "Bearer " token)}})
                           :params [:token]}}
                (sut/make-requests
                 {:cache (cache/create-atom-map-cache cache)
                  :params
                  {:token {::sut/req {:req {:method :post
                                            :url "http://example.com/security/"}}
                           ::sut/select (comp :token :body)}}})
                sut/collect!!
                (map summarize-event)
                ))
         [[::sut/cache-hit :token [200 {:token "T0k3n"}]]
          [::sut/request :example
           [:get "http://example.com/" {"Authorization" "Bearer T0k3n"}]]
          [::sut/response :example
           [200 {:request
                 {:url "http://example.com/"
                  :headers {"Authorization" "Bearer T0k3n"}
                  :method :get
                  :throw-exceptions false}}]]])))

(deftest uses-surgical-cache-key-for-lookup
  (is (= (let [cache (atom {[:example {:id 42 :config {:host "example.com"}}]
                            {:req {:method :get
                                   :url "http://example.com/42"}
                             :res {:status 200
                                   :body "I'm cached!"}}})]
           (->> {:example {:lookup-id :example
                           :req-fn (fn [{:keys [id token config]}]
                                     {:url (str "http://" (:host config) "/" id)
                                      :headers {"Authorization" (str "Bearer " token)}})
                           :params [:id :config :token]
                           :lookup-params [[:config :host] :id]}}
                (sut/make-requests
                 {:cache (cache/create-atom-map-cache cache)
                  :params
                  {:token {::sut/req {:req {:method :post
                                            :url "http://example.com/security/"}}
                           ::sut/select (comp :token :body)}
                   :id 42
                   :config {:host "example.com"
                            :debug? true}}})
                sut/collect!!
                (map summarize-event)))
         [[:courier.http/cache-hit :example [200 "I'm cached!"]]])))

(deftest uses-prepared-lookup-params-for-cache
  (is (= (let [cache (atom {[:example 42]
                            {:req {:method :get
                                   :url "http://example.com/42"}
                             :res {:status 200
                                   :body "I'm cached!"}}})]
           (->> {:example {:lookup-id :example
                           :req-fn (fn [{:keys [id token config]}]
                                     {:url (str "http://" (:host config) "/" id)
                                      :headers {"Authorization" (str "Bearer " token)}})
                           :params [:id :config :token]
                           :lookup-params [:id]
                           :prepare-lookup-params (fn [params]
                                                    (:id params))}}
                (sut/make-requests
                 {:cache (cache/create-atom-map-cache cache)
                  :params
                  {:token {::sut/req {:req {:method :post
                                            :url "http://example.com/security/"}}
                           ::sut/select (comp :token :body)}
                   :id 42
                   :config {:host "example.com"
                            :debug? true}}})
                sut/collect!!
                (map summarize-event)))
         [[:courier.http/cache-hit :example [200 "I'm cached!"]]])))

(deftest looks-up-cache-entry-without-params
  (is (= (let [cache (atom {[:example nil]
                            {:req {:method :get
                                   :url "http://example.com/42"}
                             :res {:status 200
                                   :body "I'm cached!"}}})]
           (->> {:example {:lookup-id :example
                           :req-fn (fn [params]
                                     {:url (str "http://example.com/42")})}}
                (sut/make-requests {:cache (cache/create-atom-map-cache cache)})
                sut/collect!!
                (map summarize-event)))
         [[:courier.http/cache-hit :example [200 "I'm cached!"]]])))

(deftest skips-dependent-request-when-result-is-cached
  (is (= (let [cache (atom {[:example {:id 42}]
                            {:req {:method :get
                                   :url "http://example.com/42"}
                             :res {:status 200
                                   :body "I'm cached!"}}})]
           (->> {:example {:lookup-id :example
                           :req-fn (fn [{:keys [id token]}]
                                     {:url (str "http://example.com/" id)
                                      :headers {"Authorization" (str "Bearer " token)}})
                           :params [:id :token]
                           :lookup-params [:id]}}
                (sut/make-requests
                 {:cache (cache/create-atom-map-cache cache)
                  :params
                  {:token {:req {:method :post
                                 :url "http://example.com/security/"}}
                   :id 42}})
                sut/collect!!
                (map summarize-event)))
         [[:courier.http/cache-hit :example [200 "I'm cached!"]]])))

(deftest caches-successful-result-and-reuses-it
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:content "Skontent"}}]}
           (let [cache (cache/create-atom-map-cache (atom {}))
                 spec {:example {:req {:url "https://example.com/"}
                                 :cache-fn (sut/cache-fn {:ttl 100})}}]
             (concat
              (->> (sut/make-requests {:cache cache} spec)
                   sut/collect!!
                   (map summarize-event))
              (->> (sut/make-requests {:cache cache} spec)
                   sut/collect!!
                   (map summarize-event)))))
         [[::sut/request :example [:get "https://example.com/"]]
          [::sut/response :example [200 {:content "Skontent"}]]
          [::sut/store-in-cache :example [200 {:content "Skontent"}]]
          [::sut/cache-hit :example [200 {:content "Skontent"}]]])))

(deftest does-not-cache-successful-result-with-no-ttl
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:content "Skontent"}}]}
           (let [cache (atom {})]
             (sut/request
              {:req {:url "https://example.com/"}}
              {:cache (cache/create-atom-map-cache cache)})
             @cache))
         {})))

(deftest does-not-cache-uncacheable-successful-result
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:content "Skontent"}}]}
           (let [cache (atom {})]
             (sut/request
              {:req {:url "https://example.com/"}
               :cache-fn (sut/cache-fn {:cacheable? (constantly false)
                                        :ttl 100})}
              {:cache (cache/create-atom-map-cache cache)})
             @cache))
         {})))

(deftest caches-and-looks-up-result-in-cache
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:content "Skontent"}}]}
           (let [cache (cache/create-atom-map-cache (atom {}))
                 spec {:lookup-id ::example
                       :cache-fn (sut/cache-fn {:ttl 100})
                       :req-fn (fn [params]
                                 {:url "https://example.com/"})}]
             (concat
              (->> {:example spec}
                   (sut/make-requests {:cache cache})
                   sut/collect!!
                   (map summarize-event))
              (->> {:example spec}
                   (sut/make-requests {:cache cache})
                   sut/collect!!
                   (map summarize-event)))))
         [[::sut/request :example [:get "https://example.com/"]]
          [::sut/response :example [200 {:content "Skontent"}]]
          [::sut/store-in-cache :example [200 {:content "Skontent"}]]
          [::sut/cache-hit :example [200 {:content "Skontent"}]]])))

(deftest caches-result-with-expiry
  (is (<= 3600000
          (let [now (time/now)]
            (with-responses {[:get "https://example.com/"]
                             [{:status 200
                               :body {:content "Skontent"}}]}
              (let [cache (atom {})]
                (->> {:example {:req {:url "https://example.com/"}
                                :cache-fn (sut/cache-fn {:ttl (* 60 60 1000)})}}
                     (sut/make-requests {:cache (cache/create-atom-map-cache cache)})
                     sut/collect!!)
                (- (-> @cache first second :expires-at time/millis) (time/millis now)))))
          3600010)))

(deftest caches-result-with-expiry-function
  (is (<= 100
          (let [now (time/now)]
            (with-responses {[:get "https://example.com/"]
                             [{:status 200
                               :body {:ttl 100}}]}
              (let [cache (atom {})]
                (->> {:example {:req {:url "https://example.com/"}
                                :cache-fn (sut/cache-fn {:ttl-fn #(-> % :res :body :ttl)})}}
                     (sut/make-requests {:cache (cache/create-atom-map-cache cache)})
                     sut/collect!!)
                (- (-> @cache first second :expires-at time/millis) (time/millis now)))))
          120)))

(deftest does-not-cache-if-cache-ttl-fn-throws
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {}}]}
           (->> {:example {:req {:url "https://example.com/"}
                           :cache-fn (sut/cache-fn {:ttl-fn (fn [_] (throw (ex-info "Boom!" {})))})}}
                (sut/make-requests {:cache (cache/create-atom-map-cache (atom {}))})
                sut/collect!!
                (map summarize-event)))
         [[:courier.http/request :example [:get "https://example.com/"]]
          [:courier.http/exception "Boom!" :cache-fn]
          [:courier.http/response :example [200 {}]]])))

(deftest does-not-cache-if-cacheable-fn-throws
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {}}]}
           (->> {:example {:req {:url "https://example.com/"}
                           :cache-fn (sut/cache-fn {:cacheable? (fn [_] (throw (ex-info "Boom!" {})))})}}
                (sut/make-requests {:cache (cache/create-atom-map-cache (atom {}))})
                sut/collect!!
                (map summarize-event)))
         [[:courier.http/request :example [:get "https://example.com/"]]
          [:courier.http/exception "Boom!" :cache-fn]
          [:courier.http/response :example [200 {}]]])))

(deftest does-not-cache-the-http-client-on-the-response
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:ttl 100}
                            :http-client {:stateful "Object"}}]}
           (let [cache (atom {})]
             (sut/request
              {:req {:url "https://example.com/"}
               :cache-fn (sut/cache-fn {:ttl 100})}
              {:cache (cache/create-atom-map-cache cache)})
             (some-> @cache first second :res (select-keys [:http-client]))))
         {})))

(deftest includes-cache-info-on-store
  (let [cache-status
        (with-responses {[:get "https://example.com/"]
                         [{:status 200
                           :body {:ttl 100}}]}
          (let [cache (cache/create-atom-map-cache (atom {}))
                spec {:req {:url "https://example.com/"}
                      :cache-fn (sut/cache-fn {:ttl-fn #(-> % :res :body :ttl)})}]
            (-> (sut/request spec {:cache cache})
                :cache-status)))]
    (is (true? (:stored-in-cache? cache-status)))
    (is (number? (:cached-at cache-status)))
    (is (number? (:expires-at cache-status)))
    (is (= (::cache/key cache-status)
           [::sut/req {:method :get :url "https://example.com/"}]))))

(deftest includes-cache-info-on-retrieve
  (let [cache-status
        (with-responses {[:get "https://example.com/"]
                         [{:status 200
                           :body {:ttl 100}}]}
          (let [cache (cache/create-atom-map-cache (atom {}))
                spec {:req {:url "https://example.com/"}
                      :cache-fn (sut/cache-fn {:ttl-fn #(-> % :res :body :ttl)})}]
            (sut/request spec {:cache cache})
            (-> (sut/request spec {:cache cache})
                :cache-status)))]
    (is (true? (:cache-hit? cache-status)))
    (is (number? (:cached-at cache-status)))
    (is (number? (:expires-at cache-status)))))

(deftest retries-bypassing-the-cache-for-refreshed
  (is (= (with-responses {[:post "https://example.com/security/"]
                          [{:status 200
                            :body {:token "ejY...."}}]

                          [:get "https://example.com/api"]
                          [{:status 500}
                           {:status 200
                            :body {:stuff "Stuff"}}]}
           (let [cache (atom {[::sut/req {:method :post
                                          :url "https://example.com/security/"}]
                              {:req {:method :post
                                     :url "https://example.com/security/"}
                               :res {:status 200
                                     :body {:token "T0k3n"}}}})]

             (->> {:example {:req-fn (fn [{:keys [token]}]
                                       {:url "https://example.com/api"
                                        :headers {"Authorization" (str "Bearer " token)}})
                             :params [:token]
                             :retry-fn (sut/retry-fn {:retries 1
                                                      :refresh [:token]})}}
                  (sut/make-requests
                   {:cache (cache/create-atom-map-cache cache)
                    :params
                    {:token {::sut/req {:req {:method :post
                                              :url "https://example.com/security/"}}
                             ::sut/select (comp :token :body)}}})
                  sut/collect!!
                  (map summarize-event))))
         [[::sut/cache-hit :token [200 {:token "T0k3n"}]]
          [::sut/request :example
           [:get "https://example.com/api" {"Authorization" "Bearer T0k3n"}]]
          [::sut/response :example [500]]
          [::sut/request :token [:post "https://example.com/security/"]]
          [::sut/response :token [200 {:token "ejY...."}]]
          [::sut/request :example
           [:get "https://example.com/api" {"Authorization" "Bearer ejY...."}]]
          [::sut/response :example [200 {:stuff "Stuff"}]]])))

;; request tests

(deftest makes-basic-request
  (is (= (:body (sut/request {:req {:url "http://example.com/"}}))
         {:request {:method :get
                    :throw-exceptions false
                    :url "http://example.com/"}})))

(deftest communicates-success
  (is (-> (sut/request {:req {:url "http://example.com/"}})
          :success?)))

(deftest communicates-failure
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 404
                                 :body "No"}]}
                (-> (sut/request {:req {:url "http://example.com/"}})
                    :success?)))))

(deftest communicates-failure-from-custom-assessment
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 200
                                 :body "No"}]}
                (-> (sut/request {:req {:url "http://example.com/"}
                                  :success? #(= 201 (:status %))})
                    :success?)))))

(deftest communicates-success-on-cached-success
  (is (true? (-> (sut/request
                  {:cache-fn (sut/cache-fn {:ttl (* 5 60 1000)})
                   :req {:method :get
                         :url "http://example.com"}}
                  {:cache (cache/create-atom-map-cache (atom {}))})
                 :success?))))

(deftest includes-request-log
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 200
                            :body "Ok!"}]}
           (-> (sut/request {:req {:url "http://example.com/"}})
               :log))
         [{:req {:method :get
                 :url "http://example.com/"
                 :throw-exceptions false}
           :res {:status 200
                 :body "Ok!"}
           :success? true
           :event :courier.http/response}])))

(deftest includes-request-log-events-for-cache-retrieval
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:ttl 100}}]}
           (let [cache (cache/create-atom-map-cache (atom {}))
                 spec {:req {:url "https://example.com/"}
                       :cache-fn (sut/cache-fn {:ttl-fn #(-> % :res :body :ttl)})}]
             (sut/request spec {:cache cache})
             (->> (sut/request spec {:cache cache})
                  :log
                  (map #(dissoc % :expires-at :cached-at)))))
         [{:req {:url "https://example.com/"
                 :method :get
                 :throw-exceptions false}
           :res {:status 200
                 :body {:ttl 100}}
           :success? true
           :event :courier.http/cache-hit}])))

(deftest includes-request-log-on-failure
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 404
                            :body "Ok!"}]}
           (-> (sut/request {:req {:url "http://example.com/"}})
               :log))
         [{:req {:method :get
                 :throw-exceptions false
                 :url "http://example.com/"}
           :res {:status 404
                 :body "Ok!"}
           :success? false
           :event :courier.http/response}
          {:courier.error/data {:attempts 1}
           :courier.error/reason :courier.error/retries-exhausted
           :event :courier.http/failed}])))

(deftest includes-response-like-keys
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 200
                            :headers {"Content-Type" "text/plain"}
                            :body "Ok!"}]}
           (-> (sut/request {:req {:url "http://example.com/"}})
               (select-keys [:status :headers :body])))
         {:status 200
          :headers {"Content-Type" "text/plain"}
          :body "Ok!"})))

(deftest prepares-request-with-function
  (is (= (-> (sut/request {:req-fn (fn [_]
                                     {:url "http://example.com/"})})
             :body)
         {:request {:method :get
                    :throw-exceptions false
                    :url "http://example.com/"}})))

(deftest passes-no-params-by-default
  (is (= (-> (sut/request
              {:req-fn (fn [params]
                         {:url "http://example.com/"
                          :params params})}
              {:params {:client-id "ID"
                        :client-secret "Secret"}})
             :body
             :request)
         {:method :get
          :throw-exceptions false
          :url "http://example.com/"
          :params {}})))

(deftest passes-specified-params-from-context
  (is (= (-> (sut/request
              {:req-fn (fn [params]
                         {:url "http://example.com/"
                          :params params})
               :params [:client-id]}
              {:params {:client-id "ID"
                        :client-secret "Secret"}})
             :body
             :request)
         {:method :get
          :throw-exceptions false
          :url "http://example.com/"
          :params {:client-id "ID"}})))

(deftest retries-failed-request
  (is (= (-> (with-responses {[:get "http://example.com/"]
                              [{:status 503
                                :body "Uh-oh"}
                               {:status 200
                                :body "Yass!"}]}
               (sut/request
                {:req {:url "http://example.com/"}
                 :retry-fn (sut/retry-fn {:retries 1})}))
             :body)
         "Yass!")))

(deftest does-not-retry-failed-request-that-is-not-retryable
  (is (= (-> (with-responses {[:get "http://example.com/"]
                              [{:status 503
                                :body "Uh-oh"}
                               {:status 200
                                :body "Yass!"}]}
               (sut/request
                {:req {:url "http://example.com/"}
                 :retry-fn (sut/retry-fn {:retryable? #(= 500 (-> % :res :status))
                                          :retries 1})}))
             :success?)
         false)))

(deftest handles-exceptions-when-loading-cached-objects
  (is (= (->> (sut/make-requests
               {:cache (reify cache/Cache
                         (lookup [_ _ _]
                           (throw (ex-info "Boom!" {:boom? true})))
                         (put [_ _ _ _]))}
               {:example {:req {:url "http://example.com/"}}})
              sut/collect!!
              (map summarize-event))
         [[::sut/exception "Boom!" "courier.cache/lookup"]
          [::sut/request :example [:get "http://example.com/"]]
          [::sut/response :example [200 {:request {:url "http://example.com/"
                                                   :method :get
                                                   :throw-exceptions false}}]]])))

(deftest handles-exceptions-when-storing-cached-objects
  (is (= (->> (sut/make-requests
               {:cache (reify cache/Cache
                         (lookup [_ _ _]
                           nil)
                         (put [_ _ _ _]
                           (throw (ex-info "Boom!" {:boom? true}))))}
               {:example {:req {:url "http://example.com/"}
                          :cache-fn (sut/cache-fn {:ttl 100})}})
              sut/collect!!
              (map summarize-event))
         [[:courier.http/request :example [:get "http://example.com/"]]
          [:courier.http/response :example [200 {:request {:url "http://example.com/"
                                                           :method :get
                                                           :throw-exceptions false}}]]
          [:courier.http/exception "Boom!" "courier.cache/put"]])))

(defmethod client/request [:get "https://explosives.com"] [req]
  (throw (ex-info "Boom!" {:boom? true})))

(deftest does-not-trip-on-exceptions-from-the-http-client
  (let [result (sut/request {:req {:url "https://explosives.com"}})]
    (is (not (:success? result)))
    (is (seq (:exceptions result)))))

(deftest informs-user-of-probable-misuse
  (is (= (sut/request
          {:params [:id]
           :req-fn (fn [{:keys [id]}]
                     {:url (str "http://example.com/" id)})}
          {:id 42})
         {:success? false
          :log [{:courier.error/reason :courier.error/missing-params
                 :courier.error/data [:id]
                 :event :courier.http/failed}]
          :hint (str "Make sure you pass parameters to your request as "
                     "`:params` in the options map, not directly in the map, "
                     "e.g.: {:params {:id 42}}, not {:id 42}")})))
