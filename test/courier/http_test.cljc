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
     (let [start (sut/now)]
       (if-let [event (a/<! ch)]
         (recur (conj res {:event (::sut/event event)
                           :elapsed (- (sut/now) start)}))
         res)))))

(defn summarize-req [{:keys [method url headers body]}]
  (into [method url] (remove nil? [headers body])))

(defn summarize-res [{:keys [status headers body]}]
  (into [status] (remove nil? [headers body])))

(defn summarize-event [event]
  (into ((juxt ::sut/event :path) event)
        (cond
          (:res event) [(summarize-res (:res event))]
          (:courier.error/reason event) [(:courier.error/reason event)
                                         (:courier.error/data event)]
          :default [(summarize-req (:req event))])))

;; Unit tests

(deftest cache-params-uses-all-params-by-defaut
  (is (= (sut/cache-params {::sut/params [:token :id]}
                           {:token "ejY..."
                            :id 42})
         {:required [:token :id]
          :params {:token "ejY..."
                   :id 42}})))

(deftest cache-params-uses-only-cache-params
  (is (= (sut/cache-params {::sut/params [:token :id]
                            ::sut/cache-params [:id]}
                           {:token "ejY..."
                            :id 42})
         {:required [:id]
          :params {:id 42}})))

(deftest cache-params-with-surgical-selections
  (is (= (sut/cache-params {::sut/params [:token :config :id]
                            ::sut/cache-params [[:config :host] :id]}
                           {:token "ejY..."
                            :id 42
                            :config {:host "example.com"
                                     :client-id "..."
                                     :client-secret "..."}})
         {:required [:config :id]
          :params {:id 42
                   :config {:host "example.com"}}})))

(deftest cache-params-with-no-params
  (is (= (sut/cache-params {}
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
           (->> {:example {::sut/req {:url "http://example.com/"}}}
                (sut/make-requests {})
                sut/collect!!))
         [{::sut/event ::sut/request
           :path :example
           :req {:method :get
                 :throw-exceptions false
                 :url "http://example.com/"}}
          {::sut/event ::sut/response
           :path :example
           :req {:method :get
                 :throw-exceptions false
                 :url "http://example.com/"}
           :res {:status 200
                 :body {:yep "Indeed"}}
           :success? true
           :data {:yep "Indeed"}
           :cacheable? true
           :retryable? false}])))

(deftest determines-failure-with-custom-fn
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 200
                                 :body {:yep "Indeed"}}]}
                (->> {:example {::sut/req {:url "http://example.com/"}
                                ::sut/success? #(= 201 (:status %))}}
                     (sut/make-requests {})
                     sut/collect!!
                     second
                     :success?)))))

(deftest determines-success-with-custom-fn
  (is (true? (with-responses {[:get "http://example.com/"]
                              [{:status 201
                                :body {:yep "Indeed"}}]}
               (->> {:example {::sut/req {:url "http://example.com/"}
                               ::sut/success? #(= 201 (:status %))}}
                    (sut/make-requests {})
                    sut/collect!!
                    second
                    :success?)))))

(deftest is-failure-when-success-fn-throws
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 201
                                 :body {:yep "Indeed"}}]}
                (->> {:example {::sut/req {:url "http://example.com/"}
                                ::sut/success? (fn [_] (throw (ex-info "Oops!" {})))}}
                     (sut/make-requests {})
                     sut/collect!!
                     (drop 2)
                     first
                     :success?)))))

(deftest computes-resulting-data-with-custom-fn
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 201
                            :body {:yep "Indeed"}}]}
           (->> {:example {::sut/req {:url "http://example.com/"}
                           ::sut/data-fn #(-> % :body :yep)}}
                (sut/make-requests {})
                sut/collect!!
                second
                :data))
         "Indeed")))

(deftest emits-retry-event
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 500}
                           {:status 200 :body {:ok? true}}]}
           (->> {:example {::sut/req {:url "http://example.com/"}
                           ::sut/retries 2}}
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
            (->> {:example {::sut/req {:url "http://example.com/"}
                            ::sut/retries 4
                            ::sut/retry-delays [5 10 20]}}
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
           (->> {:example {::sut/req {:url "http://example.com/"}
                           ::sut/retries 1
                           ::sut/retry-delays [5 10 20]}}
                (sut/make-requests {})
                sut/collect!!
                (map summarize-event)))
         [[::sut/request :example [:get "http://example.com/"]]
          [::sut/response :example [500]]
          [::sut/request :example [:get "http://example.com/"]]
          [::sut/response :example [500]]
          [::sut/failed :example :courier.error/retries-exhausted {:retries 1
                                                                   :attempts 2}]])))

(deftest passes-named-parameters-to-req-fn
  (is (= (->> {:example {::sut/req-fn (fn [params]
                                        {:url "http://example.com/"
                                         :params params})
                         ::sut/params [:token]}}
              (sut/make-requests
               {:params
                {:token "ejY-secret-..."
                 :other "Stuff"}})
              sut/collect!!
              second
              :data)
         {:request
          {:url "http://example.com/"
           :throw-exceptions false
           :params {:token "ejY-secret-..."}
           :method :get}})))

(deftest cannot-make-request-without-required-data
  (is (= (->> {:example {::sut/req-fn (fn [params]
                                        {:url "http://example.com/"
                                         :params params})
                         ::sut/params [:token :spoken]}}
              (sut/make-requests
               {:params
                {:token "ejY-secret-..."
                 :other "Stuff"}})
              sut/collect!!)
         [{::sut/event ::sut/failed
           :path :example
           :courier.error/reason :courier.error/missing-params
           :courier.error/data [:spoken]}])))

(deftest makes-dependent-request-first
  (is (= (with-responses {[:post "http://example.com/security/"]
                          [{:status 200 :body {:token "ejY..."}}]}
           (->> {:example {::sut/req-fn (fn [{:keys [token]}]
                                          {:url "http://example.com/"
                                           :headers {"Authorization" (str "Bearer " token)}})
                           ::sut/params [:token]}}
                (sut/make-requests
                 {:params
                  {:token {::sut/req {:method :post
                                      :url "http://example.com/security/"}
                           ::sut/data-fn (comp :token :body)}
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

(deftest loads-result-from-cache
  (is (= (let [cache (atom {[::sut/req {:method :get
                                        :url "http://example.com/"}]
                            {:req {:method :get
                                   :url "http://example.com"}
                             :res {:status 200
                                   :body "Oh yeah!"}}})]
           (->> {:example {::sut/req {:url "http://example.com/"}}}
                (sut/make-requests {:cache (cache/from-atom-map cache)})
                sut/collect!!))
         [{:req {:method :get
                 :url "http://example.com"}
           :res {:status 200
                 :body "Oh yeah!"}
           :path :example
           :success? true
           :data "Oh yeah!"
           ::sut/event ::sut/load-from-cache}])))

(deftest does-not-load-expired-result-from-cache
  (is (= (let [cache (atom {[::sut/req {:method :get
                                        :url "http://example.com/"}]
                            {:req {:method :get
                                   :url "http://example.com"}
                             :res {:status 200
                                   :body "Oh yeah!"}
                             :expires-at (time/add-millis (time/now) -10)}})]
           (->> {:example {::sut/req {:url "http://example.com/"}}}
                (sut/make-requests {:cache (cache/from-atom-map cache)})
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
           (->> {:example {::sut/id :example
                           ::sut/req-fn (fn [{:keys [id token]}]
                                          {:url (str "http://example.com/" id)
                                           :headers {"Authorization" (str "Bearer " token)}})
                           ::sut/params [:token]}}
                (sut/make-requests
                 {:cache (cache/from-atom-map cache)
                  :params
                  {:token {::sut/req {:method :post
                                      :url "http://example.com/security/"}
                           ::sut/data-fn (comp :token :body)}}})
                sut/collect!!
                (map (juxt ::sut/event :data))))
         [[:courier.http/load-from-cache "T0k3n"]
          [:courier.http/request nil]
          [:courier.http/response
           {:request
            {:url "http://example.com/"
             :throw-exceptions false
             :headers {"Authorization" "Bearer T0k3n"}
             :method :get}}]])))

(deftest uses-surgical-cache-key-for-lookup
  (is (= (let [cache (atom {[:example {:id 42 :config {:host "example.com"}}]
                            {:req {:method :get
                                   :url "http://example.com/42"}
                             :res {:status 200
                                   :body "I'm cached!"}}})]
           (->> {:example {::sut/id :example
                           ::sut/req-fn (fn [{:keys [id token config]}]
                                          {:url (str "http://" (:host config) "/" id)
                                           :headers {"Authorization" (str "Bearer " token)}})
                           ::sut/params [:id :config :token]
                           ::sut/cache-params [[:config :host] :id]}}
                (sut/make-requests
                 {:cache (cache/from-atom-map cache)
                  :params
                  {:token {::sut/req {:method :post
                                      :url "http://example.com/security/"}
                           ::sut/data-fn (comp :token :body)}
                   :id 42
                   :config {:host "example.com"
                            :debug? true}}})
                sut/collect!!
                (map (juxt ::sut/event :data))))
         [[:courier.http/load-from-cache "I'm cached!"]])))

(deftest looks-up-cache-entry-without-params
  (is (= (let [cache (atom {[:example nil]
                            {:req {:method :get
                                   :url "http://example.com/42"}
                             :res {:status 200
                                   :body "I'm cached!"}}})]
           (->> {:example {::sut/id :example
                           ::sut/req-fn (fn [params]
                                          {:url (str "http://example.com/42")})}}
                (sut/make-requests {:cache (cache/from-atom-map cache)})
                sut/collect!!
                (map (juxt ::sut/event :data))))
         [[:courier.http/load-from-cache "I'm cached!"]])))

(deftest skips-dependent-request-when-result-is-cached
  (is (= (let [cache (atom {[:example {:id 42}]
                            {:req {:method :get
                                   :url "http://example.com/42"}
                             :res {:status 200
                                   :body "I'm cached!"}}})]
           (->> {:example {::sut/id :example
                           ::sut/req-fn (fn [{:keys [id token]}]
                                          {:url (str "http://example.com/" id)
                                           :headers {"Authorization" (str "Bearer " token)}})
                           ::sut/params [:id :token]
                           ::sut/cache-params [:id]}}
                (sut/make-requests
                 {:cache (cache/from-atom-map cache)
                  :params
                  {:token {::sut/req {:method :post
                                      :url "http://example.com/security/"}
                           ::sut/data-fn (comp :token :body)}
                   :id 42}})
                sut/collect!!
                (map (juxt ::sut/event :data))))
         [[:courier.http/load-from-cache "I'm cached!"]])))

(deftest caches-successful-result-from-cache
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:content "Skontent"}}]}
           (let [cache (atom {})]
             (concat
              (->> {:example {::sut/req {:url "https://example.com/"}}}
                   (sut/make-requests {:cache (cache/from-atom-map cache)})
                   sut/collect!!
                   (map (juxt ::sut/event :data)))
              (->> {:example {::sut/req {:url "https://example.com/"}}}
                   (sut/make-requests {:cache (cache/from-atom-map cache)})
                   sut/collect!!
                   (map (juxt ::sut/event :data))))))
         [[:courier.http/request nil]
          [:courier.http/response {:content "Skontent"}]
          [:courier.http/load-from-cache {:content "Skontent"}]])))

(deftest caches-and-looks-up-result-in-cache
  (is (= (with-responses {[:get "https://example.com/"]
                          [{:status 200
                            :body {:content "Skontent"}}]}
           (let [cache (atom {})]
             (concat
              (->> {:example {::sut/id ::example
                              ::sut/req-fn (fn [params] {:url "https://example.com/"})}}
                   (sut/make-requests {:cache (cache/from-atom-map cache)})
                   sut/collect!!
                   (map summarize-event))
              (->> {:example {::sut/id ::example
                              ::sut/req-fn (fn [params] {:url "https://example.com/"})}}
                   (sut/make-requests {:cache (cache/from-atom-map cache)})
                   sut/collect!!
                   (map summarize-event)))))
         [[::sut/request :example [:get "https://example.com/"]]
          [::sut/response :example [200 {:content "Skontent"}]]
          [::sut/load-from-cache :example [200 {:content "Skontent"}]]])))

(deftest caches-result-with-expiry
  (is (<= 3600000
          (let [now (time/now)]
            (with-responses {[:get "https://example.com/"]
                             [{:status 200
                               :body {:content "Skontent"}}]}
              (let [cache (atom {})]
                (->> {:example {::sut/req {:url "https://example.com/"}
                                ::sut/cache-for (* 60 60 1000)}}
                     (sut/make-requests {:cache (cache/from-atom-map cache)})
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
                (->> {:example {::sut/req {:url "https://example.com/"}
                                ::sut/cache-for-fn #(-> % :body :ttl)}}
                     (sut/make-requests {:cache (cache/from-atom-map cache)})
                     sut/collect!!)
                (- (-> @cache first second :expires-at time/millis) (time/millis now)))))
          110)))

(deftest does-not-cache-the-http-client-on-the-response
  (is (nil? (with-responses {[:get "https://example.com/"]
                             [{:status 200
                               :body {:ttl 100}
                               :http-client {:stateful "Object"}}]}
              (let [cache (atom {})]
                (sut/request
                 {::sut/req {:url "https://example.com/"}
                  ::sut/cache-for-fn #(-> % :body :ttl)}
                 {:cache (cache/from-atom-map cache)})
                (-> @cache first second :res :http-client))))))

(deftest includes-cache-info-on-retrieve
  (let [cache-status
        (with-responses {[:get "https://example.com/"]
                         [{:status 200
                           :body {:ttl 100}
                           :http-client {:stateful "Object"}}]}
          (let [cache (atom {})]
            (sut/request
             {::sut/req {:url "https://example.com/"}
              ::sut/cache-for-fn #(-> % :body :ttl)}
             {:cache (cache/from-atom-map cache)})
            (-> (sut/request
                 {::sut/req {:url "https://example.com/"}
                  ::sut/cache-for-fn #(-> % :body :ttl)}
                 {:cache (cache/from-atom-map cache)})
                :courier.res/cache-status)))]
    (is (true? (:cached? cache-status)))
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

             (->> {:example {::sut/req-fn (fn [{:keys [token]}]
                                            {:url "https://example.com/api"
                                             :headers {"Authorization" (str "Bearer " token)}})
                             ::sut/params [:token]
                             ::sut/retries 1
                             ::sut/retry-refresh-fn (fn [_] [:token])}}
                  (sut/make-requests
                   {:cache (cache/from-atom-map cache)
                    :params
                    {:token {::sut/req {:method :post
                                        :url "https://example.com/security/"}
                             ::sut/data-fn (comp :token :body)}}})
                  sut/collect!!
                  (map (juxt ::sut/event :path :success? :data)))))
         [[::sut/load-from-cache :token true "T0k3n"]
          [::sut/request :example nil nil]
          [::sut/response :example false nil]
          [::sut/request :token nil nil]
          [::sut/response :token true "ejY...."]
          [::sut/request :example nil nil]
          [::sut/response :example true {:stuff "Stuff"}]])))

;; request tests

(deftest makes-basic-request
  (is (= (-> (sut/request {::sut/req {:url "http://example.com/"}})
             :courier.res/data)
         {:request {:method :get
                    :throw-exceptions false
                    :url "http://example.com/"}})))

(deftest communicates-success
  (is (-> (sut/request {::sut/req {:url "http://example.com/"}})
          :courier.res/success?)))

(deftest communicates-failure
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 404
                                 :body "No"}]}
                (-> (sut/request {::sut/req {:url "http://example.com/"}})
                    :courier.res/success?)))))

(deftest communicates-failure-from-custom-assessment
  (is (false? (with-responses {[:get "http://example.com/"]
                               [{:status 200
                                 :body "No"}]}
                (-> (sut/request {::sut/req {:url "http://example.com/"}
                                  ::sut/success? #(= 201 (:status %))})
                    :courier.res/success?)))))

(deftest has-no-result-when-request-failed
  (is (nil? (with-responses {[:get "http://example.com/"]
                             [{:status 404
                               :body "No"}]}
              (-> (sut/request {::sut/req {:url "http://example.com/"}})
                  :courier.res/data)))))

(deftest has-result-when-request-succeeded-by-custom-assessment
  (is (= (-> (with-responses {[:get "http://example.com/"]
                              [{:status 404
                                :body "Oh well"}]}
               (sut/request {::sut/req {:url "http://example.com/"}
                             ::sut/success? #(= 404 (:status %))}))
             :courier.res/data)
         "Oh well")))

(deftest has-no-result-when-request-failed-by-custom-assessment
  (is (nil? (with-responses {[:get "http://example.com/"]
                             [{:status 200
                               :body "Oh well"}]}
              (-> (sut/request {::sut/req {:url "http://example.com/"}
                                ::sut/success? #(= 201 (:status %))})
                  :courier.res/data)))))

(deftest includes-request-log
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 200
                            :body "Ok!"}]}
           (-> (sut/request {::sut/req {:url "http://example.com/"}})
               :courier.res/log))
         [{:req {:method :get
                 :throw-exceptions false
                 :url "http://example.com/"}
           :res {:status 200
                 :body "Ok!"}
           :data "Ok!"
           :success? true
           :retryable? false
           :cacheable? true}])))

(deftest includes-request-log-on-failure
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 404
                            :body "Ok!"}]}
           (-> (sut/request {::sut/req {:url "http://example.com/"}})
               :courier.res/log))
         [{:req {:method :get
                 :throw-exceptions false
                 :url "http://example.com/"}
           :res {:status 404
                 :body "Ok!"}
           :success? false
           :data nil
           :cacheable? false
           :retryable? true}])))

(deftest includes-response-like-keys
  (is (= (with-responses {[:get "http://example.com/"]
                          [{:status 200
                            :headers {"Content-Type" "text/plain"}
                            :body "Ok!"}]}
           (-> (sut/request {::sut/req {:url "http://example.com/"}})
               (select-keys [:status :headers :body])))
         {:status 200
          :headers {"Content-Type" "text/plain"}
          :body "Ok!"})))

(deftest prepares-request-with-function
  (is (= (-> (sut/request {::sut/req-fn (fn [_]
                                          {:url "http://example.com/"})})
             :courier.res/data)
         {:request {:method :get
                    :throw-exceptions false
                    :url "http://example.com/"}})))

(deftest passes-no-params-by-default
  (is (= (-> (sut/request
              {::sut/req-fn (fn [params]
                              {:url "http://example.com/"
                               :params params})}
              {:params {:client-id "ID"
                        :client-secret "Secret"}})
             :courier.res/data
             :request)
         {:method :get
          :throw-exceptions false
          :url "http://example.com/"
          :params {}})))

(deftest passes-specified-params-from-context
  (is (= (-> (sut/request
              {::sut/req-fn (fn [params]
                              {:url "http://example.com/"
                               :params params})
               ::sut/params [:client-id]}
              {:params {:client-id "ID"
                        :client-secret "Secret"}})
             :courier.res/data
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
                {::sut/req {:url "http://example.com/"}
                 ::sut/retries 1}))
             :courier.res/data)
         "Yass!")))

(deftest does-not-retry-failed-request-that-is-not-retryable
  (is (= (-> (with-responses {[:get "http://example.com/"]
                              [{:status 503
                                :body "Uh-oh"}
                               {:status 200
                                :body "Yass!"}]}
               (sut/request
                {::sut/req {:url "http://example.com/"}
                 ::sut/retryable? #(= 500 (:status %))
                 ::sut/retries 1}))
             :courier.res/success?)
         false)))

(deftest formats-results-from-cache
  (is (= (let [cache (atom {[::sut/req {:method :get
                                        :url "http://example.com/"}]
                            {:req {:method :get
                                   :url "http://example.com"}
                             :res {:status 200
                                   :body "Oh yeah!"}}})]
           (->> (sut/request
                 {::sut/req {:url "http://example.com/"}}
                 {:cache (cache/from-atom-map cache)})))
         {:status 200
          :body "Oh yeah!"
          :courier.res/success? true
          :courier.res/data "Oh yeah!"
          :courier.res/log
          [{:req {:method :get, :url "http://example.com"}
            :res {:status 200, :body "Oh yeah!"}
            :success? true
            :data "Oh yeah!"}]
          :courier.res/cache-status {:cached-at nil
                                     :cached? true
                                     :expires-at nil}})))

(defmethod client/request [:get "https://explosives.com"] [req]
  (throw (ex-info "Boom!" {:boom? true})))

(deftest does-not-trip-on-exceptions-from-the-http-client
  (let [result (sut/request {::sut/req {:url "https://explosives.com"}})]
    (is (not (:courier.res/success? result)))
    (is (nil? (:courier.res/data result)))
    (is (seq (:courier.res/exceptions result)))))
