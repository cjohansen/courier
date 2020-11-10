(ns courier.http
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [clojure.string :as str]
            [courier.cache :as cache]
            [courier.client :as client]
            [courier.fingerprint :as fingerprint]
            [clojure.set :as set])
  #?(:clj (:import java.time.Instant)))

(defn now []
  #?(:cljs (.getTime (js/Date.))
     :clj (.toEpochMilli (java.time.Instant/now))))

(defn emit [ch event exchange]
  (a/put! ch (-> exchange
                 (dissoc :spec)
                 (assoc ::event event))))

(defn try-emit [log f & args]
  (try
    (apply f args)
    (catch Throwable e
      (emit log ::exception {:throwable e
                             :source (cache/fname f)})
      false)))

(defn prepare-request [log {::keys [req req-fn params]} ctx]
  (let [req (or req (try-emit log req-fn (select-keys ctx params)))]
    (update req :method #(or % :get))))

(defn success? [{:keys [spec res]}]
  (let [f (or (::success? spec) client/success?)]
    (f res)))

(defn get-data [{:keys [spec success? res]}]
  (when success?
    (let [f (or (::data-fn spec) :body)]
      (f res))))

(defn cacheable? [{:keys [success? spec res]}]
  (and success?
       (if-let [cacheable? (::cacheable? spec)]
         (cacheable? res)
         true)))

(defn retryable? [{:keys [success? spec res]}]
  (and (not success?)
       (if-let [retryable? (::retryable? spec)]
         (retryable? res)
         true)))

(defn requests-for [exchanges path]
  (seq (filter (comp #{path} :path) exchanges)))

(defn get-retry-delay [exchanges spec path]
  (when-let [delays (::retry-delays spec)]
    (let [retry-n (count (requests-for exchanges path))]
      (when (and (< 0 retry-n) delays)
        (get delays (dec (min retry-n (count delays))))))))

(defn prepare-result [log exchange]
  (let [result (assoc exchange :success? (try-emit log success? exchange))]
    (assoc result :data (try-emit log get-data result))))

(defn select-paths [m ks]
  (reduce #(let [k (if (coll? %2) %2 [%2])]
             (assoc-in %1 k (get-in m k))) nil ks))

(defn cache-params [{::keys [params cache-params]} ctx]
  (let [required (or cache-params params)]
    {:required (map (fn [k] (if (coll? k) (first k) k)) required)
     :params (select-paths ctx required)}))

(defn maybe-cache-result [spec ctx cache result]
  (when (and (:cacheable? result) cache)
    (let [k (cache/cache-key spec (:params (cache-params spec ctx)))]
      (cache/put cache k (select-keys result [:req :res :path])))))

(defn make-request [log spec ctx path exchanges cache]
  (a/go
    (when-let [delay (get-retry-delay exchanges spec path)]
      (a/<! (a/timeout delay)))
    (let [req (prepare-request log spec ctx)
          exchange {:path path
                    :spec spec
                    :req req}]
      (emit log ::request exchange)
      (let [result (prepare-result log (assoc exchange :res (client/request req)))
            result (assoc result
                          :cacheable? (try-emit log cacheable? result)
                          :retryable? (try-emit log retryable? result))]
        (emit log ::response result)
        (maybe-cache-result spec ctx cache result)
        result))))

(defn params-available? [ctx {::keys [params]}]
  (every? #(contains? ctx %) params))

(defn eligible? [k spec exchanges]
  (let [exchanges (requests-for exchanges k)]
    (or (nil? exchanges)
        (and (:retryable? (last exchanges))
             (<= (count exchanges) (get spec ::retries 0))))))

(defn find-pending [specs ctx ks exchanges]
  (->> (remove #(contains? ctx %) ks)
       (filter #(params-available? ctx (get specs %)))
       (filter #(eligible? % (get specs %) exchanges))))

(defn extract-result-data [chans]
  (a/go-loop [chans chans
              result {}
              exchanges []]
    (if (seq chans)
      (let [[v ch] (a/alts! chans)]
        (recur (remove #{ch} chans)
               (cond-> result
                 (:success? v) (assoc (:path v) (:data v)))
               (conj exchanges v)))
      {:result result
       :exchanges exchanges})))

(defn get-cached [log cache specs k ctx]
  (let [spec (k specs)
        {:keys [required params]} (cache-params spec ctx)]
    (when (and (not (:refresh? spec))
               (= (count required) (count params)))
      (when-let [cached (cache/lookup cache (cache/cache-key spec params))]
        (prepare-result log (assoc cached :path k :spec spec))))))

(defn lookup-cache [log specs ctx ks all-exchanges cache]
  (when cache
    (when-let [cached (seq (keep #(get-cached log cache specs % ctx) ks))]
      (doseq [x cached]
        (emit log ::load-from-cache x))
      (a/go
        (let [cached-paths (set (map :path cached))]
          {:specs specs
           :ctx (merge ctx (->> cached
                                (map (juxt :path :data))
                                (into {})))
           :ks (remove cached-paths ks)
           :exchanges all-exchanges})))))

(defn find-stale-keys [specs exchanges]
  (->> exchanges
       (remove :success?)
       (mapcat (fn [{:keys [spec retryable?] :as result}]
                 (when retryable?
                   (cond
                     (::retry-refresh-fn spec) ((::retry-refresh-fn spec) result)
                     (::retry-refresh spec) (::retry-refresh spec)
                     :default nil))))))

(defn mark-for-refresh [specs ks]
  (->> ks
       (keep (fn [k]
               (when-let [spec (k specs)]
                 [k (assoc spec :refresh? true)])))
       (into {})
       (merge specs)))

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
  (when-let [unresolved (->> (mapcat (comp ::params specs) ks)
                             (remove #(contains? ctx %))
                             (filter #(contains? specs %))
                             (remove (set ks))
                             seq)]
    (a/go {:specs specs
           :ctx ctx
           :ks (set (concat ks unresolved))
           :exchanges exchanges})))

(defn explain-failed-request [specs ctx exchanges k]
  (let [spec (k specs)]
    (cond
      (not (params-available? ctx spec))
      {:courier.error/reason :courier.error/missing-params
       :courier.error/data (remove #(contains? ctx %) (::params spec))}

      (< (get spec ::retries 0) (count (requests-for exchanges k)))
      {:courier.error/reason :courier.error/retries-exhausted
       :courier.error/data {:retries (get spec ::retries 0)
                            :attempts (count (requests-for exchanges k))}}

      ;; Shouldn't happen (tm)
      :default {:courier.error/reason :courier.error/unknown})))

(defn spec? [v]
  (and (map? v)
       (or (::req v) (::req-fn v))))

(defn extract-specs [ctx]
  (->> ctx
       (filter (comp spec? second))
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

(defn prepare-full-result-for [k events]
  (let [reqs (filter (comp #{::response} ::event) events)
        res (last (filter (comp #{k} :path) reqs))]
    (merge
     (select-keys (:res res) [:status :headers :body])
     {:courier.res/success? (:success? res)
      :courier.res/data (:data res)
      :courier.res/reqs (map #(dissoc % ::event :path) reqs)})))

(defn request [spec & [opt]]
  (->> {::req spec}
       (make-requests opt)
       collect!!
       (prepare-full-result-for ::req)))

(defn request-with-log [spec & [opt]]
  (let [ch (a/chan 512)]
    [ch
     (a/go
       (->> (siphon!! (make-requests opt {::req spec}) ch)
            (prepare-full-result-for ::req)))]))
