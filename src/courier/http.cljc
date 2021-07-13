(ns courier.http
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [courier.cache :as cache]
            [courier.client :as client]
            [courier.time :as time])
  #?(:clj (:import java.time.Instant)))

(defn emit [ch event exchange]
  (a/put! ch (-> exchange
                 (dissoc :spec)
                 (assoc :event event))))

(defn try-emit
  "Like try-catch, except emit an exception event instead of throwing exceptions."
  [log f & args]
  (try
    (apply f args)
    (catch Throwable e
      (emit log ::exception {:throwable e
                             :source (cache/fname f)})
      nil)))

(defn with-name [f n]
  (with-meta f {:name n}))

(defn prepare-request [log {:keys [req req-fn params]} ctx]
  (when (or (map? req) (ifn? req-fn))
    (let [req (or req (try-emit log (with-name req-fn "req-fn") (select-keys ctx params)))]
      (-> req
          (update :method #(or % :get))
          (assoc :throw-exceptions false)))))

(defn success? [{:keys [spec req res]}]
  (let [f (or (:success? spec) (comp client/success? :res))]
    (f {:res res :req req})))

(defn retryable? [{:keys [req]}]
  (= :get (:method req)))

(defn requests-for [exchanges path]
  (seq (filter (comp #{path} :path) exchanges)))

(defn get-retry-delay [exchanges path]
  (-> (requests-for exchanges path) last :retry :delay))

(defn prepare-result [log exchange]
  (let [success? (try-emit log success? exchange)]
    (assoc exchange :success? (boolean success?))))

(defn select-paths [m ks]
  (reduce #(let [k (if (coll? %2) %2 [%2])]
             (assoc-in %1 k (get-in m k))) nil ks))

(defn lookup-params [{:keys [params lookup-params]} ctx]
  (let [required (or lookup-params params)
        params (select-paths ctx required)]
    {:required (map (fn [k] (if (coll? k) (first k) k)) required)
     :params params}))

(defn prepare-lookup-params [{:keys [prepare-lookup-params]} params]
  (cond-> params
    (ifn? prepare-lookup-params) prepare-lookup-params))

(defn maybe-cache-result [log spec ctx cache result]
  (when (and cache (-> result :cache :cache?))
    (let [{:keys [params]} (lookup-params spec ctx)]
      (try
        (->> (cache/store cache spec (prepare-lookup-params spec params) result)
             (emit log ::store-in-cache))
        (catch Exception e
          (emit log ::exception {:throwable e
                                 :source "courier.cache/put"})
          nil)))))

(defn fulfill-exchange [log exchange]
  (a/go
    (let [res (a/<! #?(:cljs (client/request (:req exchange))
                       :clj (a/thread
                              (try
                                (client/request (:req exchange))
                                (catch Exception e
                                  e)))))]
      (if (instance? #?(:clj Exception
                        :cljs Error) res)
        (assoc exchange :exception res)
        (prepare-result log (assoc exchange :res res))))))

(def validations
  {:retry {:retry? boolean?
           :delay number?
           :refresh #(and (coll? %) (every? keyword? %))}
   :cache {:cache? boolean?
           :ttl number?}})

(defn valid-keys? [m validations]
  (->> m
       (remove (comp nil? second))
       (every? (fn [[k v]]
                 (if-let [f (get validations k)]
                   (f v)
                   true)))))

(defn get-retry-info [{:keys [spec path req res success?] :as exchange} log exchanges]
  (when-not success?
    (when-let [f (:retry-fn spec)]
      (let [ctx {:req req
                 :res res
                 ;; Increase the count to include the current attempt
                 :num-attempts (inc (count (requests-for exchanges path)))}
            retry (try-emit log (with-meta f {:name "retry-fn"}) ctx)]
        (if (valid-keys? retry (:retry validations))
          retry
          (do
            (emit log ::invalid-data (assoc exchange :retry retry))
            nil))))))

(defn get-cache-info [{:keys [spec path req res success?] :as exchange} log]
  (when success?
    (when-let [f (:cache-fn spec)]
      (let [ctx {:req req :res res}
            cache (try-emit log (with-meta f {:name "cache-fn"}) ctx)]
        (if (valid-keys? cache (:cache validations))
          cache
          (do
            (emit log ::invalid-data (assoc exchange :cache cache))
            nil))))))

(defn make-request [log spec ctx path exchanges cache]
  (a/go
    (when-let [delay (get-retry-delay exchanges path)]
      (a/<! (a/timeout delay)))
    (let [req (prepare-request log spec ctx)
          exchange {:path path
                    :spec spec
                    :req req}]
      (if req
        (do
          (emit log ::request exchange)
          (let [result (a/<! (fulfill-exchange log exchange))
                result (merge result
                              (when-let [retry (get-retry-info result log exchanges)]
                                {:retry retry})
                              (when-let [cache (get-cache-info result log)]
                                {:cache cache}))]
            (emit log (cond
                        (:res result) ::response
                        (:exception result) ::exception) result)
            (maybe-cache-result log spec ctx cache result)
            result))
        {:path path
         :spec spec}))))

(defn params-available? [ctx {:keys [params]}]
  (every? #(contains? ctx %) params))

(defn eligible? [k spec exchanges]
  (let [exchanges (requests-for exchanges k)]
    (or (nil? exchanges)
        (-> exchanges last :retry :retry?))))

(defn find-pending [specs ctx ks exchanges]
  (->> (remove #(contains? ctx %) ks)
       (filter #(params-available? ctx (get specs %)))
       (filter #(eligible? % (get specs %) exchanges))))

(defn prepare-for-context [{:keys [spec res]}]
  (if-let [f (::select spec)]
    (f res)
    res))

(defn extract-result-data [chans]
  (a/go-loop [chans chans
              result {}
              exchanges []]
    (if (seq chans)
      (let [[v ch] (a/alts! chans)]
        (recur (remove #{ch} chans)
               (cond-> result
                 (:success? v) (assoc (:path v) (prepare-for-context v)))
               (conj exchanges v)))
      {:result result
       :exchanges exchanges})))

(defn get-cached [log cache specs k ctx]
  (let [spec (k specs)
        {:keys [required params]} (lookup-params spec ctx)]
    (when (and (not (:refresh? spec))
               (= (count required) (count params)))
      (try
        (when-let [cached (cache/retrieve cache spec (prepare-lookup-params spec params))]
          (prepare-result log (assoc cached :path k :spec spec)))
        (catch Exception e
          (emit log ::exception {:throwable e
                                 :source "courier.cache/lookup"})
          nil)))))

(defn lookup-cache [log specs ctx ks all-exchanges cache]
  (when cache
    (when-let [cached (seq (keep #(get-cached log cache specs % ctx) ks))]
      (doseq [x cached]
        (emit log ::cache-hit x))
      (a/go
        (let [cached-paths (set (map :path cached))]
          {:specs specs
           :ctx (->> cached
                     (map (juxt :path prepare-for-context))
                     (into ctx))
           :ks (remove cached-paths ks)
           :exchanges all-exchanges})))))

(defn find-stale-keys [specs exchanges]
  (->> exchanges
       (remove :success?)
       (mapcat (fn [{:keys [spec retry] :as result}]
                 (when (:retry? retry)
                   (:refresh retry))))))

(defn mark-for-refresh [specs ks]
  (->> ks
       (keep (fn [k]
               (when-let [spec (k specs)]
                 [k (assoc spec :refresh? true)])))
       (into specs)))

(defn request-pending [log specs ctx ks all-exchanges cache]
  (when-let [pending (seq (find-pending specs ctx ks all-exchanges))]
    (a/go
      (let [chans (map #(make-request log (get specs %) ctx % all-exchanges cache) pending)
            {:keys [result exchanges]} (a/<! (extract-result-data chans))
            stale (find-stale-keys specs exchanges)]
        {:specs (mark-for-refresh specs stale)
         :ctx (apply dissoc (merge ctx result) stale)
         :ks (remove (set (keys result)) ks)
         :exchanges (concat all-exchanges exchanges)}))))

(defn expand-selection [log specs ctx ks exchanges]
  (when-let [unresolved (->> (mapcat (comp :params specs) ks)
                             (remove #(contains? ctx %))
                             (filter #(contains? specs %))
                             (remove (set ks))
                             seq)]
    (a/go {:specs specs
           :ctx ctx
           :ks (set (concat ks unresolved))
           :exchanges exchanges})))

(defn unknown-host? [{:keys [exception]}]
  (and exception
       #?(:clj (instance? java.net.UnknownHostException exception)
          :cljs false)))

(defn explain-failed-request [specs ctx exchanges k]
  (let [spec (k specs)
        reqs (requests-for exchanges k)]
    (cond
      (not (params-available? ctx spec))
      {:courier.error/reason :courier.error/missing-params
       :courier.error/data (remove #(contains? ctx %) (:params spec))}

      (unknown-host? (last reqs))
      {:courier.error/reason :courier.error/unknown-host
       :courier.error/data {:req (:req (last reqs))}}

      (not (contains? (last reqs) :req))
      {:courier.error/reason :courier.error/missing-req-or-req-fn
       :courier.error/data (select-keys (last reqs) [:spec])}

      (= (:max-retries (:retry (last reqs)) 0) 0)
      (assoc (last reqs)
             :courier.error/reason :courier.error/request-failed
             :courier.error/data (last reqs))

      (< (:max-retries (:retry (last reqs)) 0) (count reqs))
      (assoc (last reqs)
             :courier.error/reason :courier.error/retries-exhausted
             :courier.error/data (cond-> {:attempts (count (requests-for exchanges k))
                                          :last-res (-> reqs last :res)
                                          :req (:req (last reqs))}
                                   (-> reqs last :retry :max-retries)
                                   (assoc :max-retries (-> reqs last :retry :max-retries))))

      ;; Shouldn't happen (tm)
      :default {:courier.error/reason :courier.error/unknown
                :courier.error/data (last reqs)})))

(defn dep? [v]
  (and (map? v)
       (::req v)))

(defn extract-specs [ctx]
  (->> ctx
       (filter (comp dep? second))
       (map (fn [[k v]] [k (assoc (::req v) ::select (::select v))]))
       (into {})))

(defn make-requests [{:keys [cache params]} specs]
  (assert (or (nil? cache) (satisfies? cache/Cache cache))
          "cache does not implement the courier.cache/Cache protocol")
  (let [log (a/chan 512)
        param-specs (extract-specs params)
        ks (keys specs)]
    (a/go-loop [specs (merge specs param-specs)
                ctx (apply dissoc params (keys param-specs))
                ks ks
                all-exchanges []]
      (if-let [ch (or (lookup-cache log specs ctx ks all-exchanges cache)
                      (request-pending log specs ctx ks all-exchanges cache)
                      (expand-selection log specs ctx ks all-exchanges))]
        (let [{:keys [specs ctx ks exchanges]} (a/<! ch)]
          (recur specs ctx ks exchanges))
        (do
          (doseq [k ks]
            (emit log ::failed (-> (explain-failed-request specs ctx all-exchanges k)
                                   (assoc :path k))))
          (a/close! log))))
    log))

(defn siphon!! [in out]
  (a/<!!
   (a/go-loop [res []]
     (if-let [event (a/<! in)]
       (do
         (when out
           (a/put! out event))
         (recur (conj res event)))
       res))))

(defn collect!! [ch]
  (siphon!! ch nil))

(defn strip-duplicate-data [result]
  (if (:courier.error/data result)
    (dissoc result :req :res :success?)
    result))

(defn prepare-full-result-for [k opt events]
  (let [reqs (filter (comp #{::response ::cache-hit ::store-in-cache ::failed} :event) events)
        res (last (filter (comp #{k} :path) reqs))
        cache-status (merge
                      (:cache-status res)
                      (select-keys res [:cached-at :expires-at])
                      (->> (keys res)
                           (filter (comp #{"courier.cache"} namespace))
                           (select-keys res)))]
    (merge
     (select-keys (:res res) [:status :headers :body])
     {:success? (boolean (:success? res))
      :log (->> reqs
                (remove (comp #{::store-in-cache} :event))
                (map #(dissoc % :path))
                (map strip-duplicate-data))}
     (when (= ::cache-hit (:event res))
       {:cache-status (assoc cache-status :cache-hit? true)})
     (when (= ::store-in-cache (:event res))
       {:cache-status (assoc cache-status :stored-in-cache? true)})
     (when-let [exceptions (seq (filter (comp #{::exception} :event) events))]
       {:exceptions (map #(dissoc % :event :path) exceptions)})
     (when (and (= ::failed (:event res))
                (= :courier.error/missing-params (:courier.error/reason res)))
       (when-let [possibly-misplaced (some (set (keys opt)) (:courier.error/data res))]
         {:hint (str "Make sure you pass parameters to your request as `:params` "
                     "in the options map, not directly in the map, e.g.: "
                     "{:params {" possibly-misplaced " " (get opt possibly-misplaced) "}}, not "
                     "{" possibly-misplaced " " (get opt possibly-misplaced) "}")})))))

(defn request [spec & [opt]]
  (->> {::req spec}
       (make-requests opt)
       collect!!
       (prepare-full-result-for ::req opt)))

(defn request-with-log [spec & [opt]]
  (let [ch (a/chan 512)]
    [ch
     (a/thread
       (->> (siphon!! (make-requests opt {::req spec}) ch)
            (prepare-full-result-for ::req opt)))]))

(defn retry-fn [{:keys [delays refresh refresh-fn] :as opt}]
  (let [retryable? (:retryable? opt retryable?)
        retries (or (:retries opt) 0)
        refresh-fn (or refresh-fn
                       (when refresh (constantly refresh))
                       (constantly nil))]
    (fn [{:keys [num-attempts] :as exchange}]
      (when (retryable? exchange)
        (merge
         {:retry? (<= (:num-attempts exchange) retries)
          :max-retries retries}
         (when delays
           {:delay (get delays (dec (min num-attempts (count delays))))})
         (when-let [refresh (refresh-fn exchange)]
           {:refresh refresh}))))))

(defn cache-fn [{:keys [ttl ttl-fn cacheable?] :as opt}]
  (let [ttl-fn (or ttl-fn (when ttl (constantly ttl)))
        cacheable? (or cacheable? (constantly true))]
    (fn [{:keys [res] :as exchange}]
      (when (and (cacheable? exchange) ttl-fn)
        (let [ttl (ttl-fn exchange)]
          {:cache? true
           :expires-at (time/add-millis (time/now) ttl)
           :ttl ttl})))))
