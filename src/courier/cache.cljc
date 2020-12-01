(ns courier.cache
  (:require [clojure.string :as str]
            [courier.time :as time]
            [courier.fingerprint :as fingerprint]))

(defprotocol Cache
  (lookup [_ spec params])
  (put [_ spec params res]))

(defn fname [f]
  (if-let [meta-name (-> f meta :name)]
    (keyword (some-> f meta :ns str) (str meta-name))
    (when-let [name (some-> f str (str/replace #"_" "-"))]
      (let [name (-> (re-find #"(?:#')?(.*)" name)
                     second
                     (str/replace #"-QMARK-" "?"))]
        #?(:clj
           (if-let [[_ ns n] (re-find #"(.*)\$(.*)@" name)]
             (keyword ns n)
             (keyword name))
           :cljs
           (if-let [[_ res] (re-find #"function (.+)\(" name)]
             (let [[f & ns ] (-> res (str/split #"\$") reverse)]
               (keyword (str/join "." (reverse ns)) f))
             (if (re-find #"function \(" name)
               (keyword (str (random-uuid)))
               (keyword (str/replace name #" " "-")))))))))

(defn cache-id [{:keys [id req-fn]}]
  (or id
      (when req-fn (fname req-fn))
      :courier.http/req))

(defn- normalize-headers [headers]
  (->> headers
       (map (fn [[h v]] [(str/lower-case h) v]))
       (into {})))

(defn get-cache-relevant-params [{:keys [id req-fn req]} params]
  (if (or id req-fn)
    params
    (let [[url query-string] (str/split (:url req) #"\?")]
      (merge
       {:method :get}
       (select-keys req [:method :url :as :content-type :query-params])
       (when query-string
         {:query-params
          (->> (str/split query-string #"&")
               (map #(let [[k & args] (str/split % #"=")]
                       [(keyword k) (str/join args)]))
               (into {}))})
       (when-let [headers (:headers req)]
         {:headers (normalize-headers headers)})
       (let [sensitive (select-keys req [:body :form-params :basic-auth])]
         (when-not (empty? sensitive)
           {:hash (fingerprint/fingerprint sensitive)}))
       (when-not (empty? params)
         {:lookup-params params})))))

(defn cache-key [spec params]
  [(cache-id spec) (get-cache-relevant-params spec params)])

(defn expired? [res]
  (and (int? (:expires-at res))
       (not (time/before? (time/now) (:expires-at res)))))

(defn retrieve [cache spec params]
  (when-let [res (lookup cache spec params)]
    (when (not (expired? res))
      res)))

(defn cacheable [result]
  (-> (select-keys result [:req :res])
      (assoc :expires-at (-> result :cache :expires-at))
      (update :res dissoc :http-client)
      (assoc :cached-at (time/millis (time/now)))))

(defn store [cache spec params res]
  (let [cacheable-result (cacheable res)
        cache-data (put cache spec params cacheable-result)]
    (assoc cacheable-result :path (:path res) :cache-status cache-data)))

(defn create-atom-map-cache [ref]
  (assert (instance? clojure.lang.Atom ref)
          (format "ref must be an atom, was %s" (type ref)))
  (assert (or (map? @ref) (nil? @ref)) "ref must contain nil or a map")
  (reify Cache
    (lookup [_ spec params]
      (get @ref (cache-key spec params)))
    (put [_ spec params res]
      (let [k (cache-key spec params)]
        (swap! ref assoc k res)
        {::key k}))))
