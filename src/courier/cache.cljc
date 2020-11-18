(ns courier.cache
  (:require [clojure.string :as str]
            [courier.time :as time]))

(defprotocol Cache
  (lookup [_ spec params])
  (put [_ spec params res]))

(defn fname [f]
  (if-let [meta-name (-> f meta :name)]
    meta-name
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
  (cond
    (or id req-fn) params

    :default
    (let [[url query-string] (str/split (:url req) #"\?")]
      (merge
       {:method (:method req :get)
        :url url}
       (when query-string
         {:query-params
          (->> (str/split query-string #"&")
               (map #(let [[k & args] (str/split % #"=")]
                       [(keyword k) (str/join args)]))
               (into {}))})
       (let [params (cond-> (select-keys req [:headers :body])
                      (:headers req) (update :headers normalize-headers))]
         (when-not (empty? params)
           params))
       (when-not (empty? params)
         {:cache-params params})))))

(defn cache-key [spec params]
  [(cache-id spec) (get-cache-relevant-params spec params)])

(defn from-atom-map [ref]
  (assert (instance? clojure.lang.Atom ref)
          (format "ref must be an atom, was %s" (type ref)))
  (assert (or (map? @ref) (nil? @ref)) "ref must contain nil or a map")
  (reify Cache
    (lookup [_ spec params]
      (get @ref (cache-key spec params)))
    (put [_ spec params res]
      (swap! ref assoc (cache-key spec params) res))))

(defn retrieve [cache spec params]
  (when-let [res (lookup cache spec params)]
    (when (or (not (int? (:expires-at res)))
              (time/before? (time/now) (:expires-at res)))
      res)))

(defn cacheable [result]
  (-> (select-keys result [:req :res])
      (assoc :expires-at (-> result :cache :expires-at))
      (update :res dissoc :http-client)
      (assoc :cached-at (time/millis (time/now)))))

(defn store [cache spec params res]
  (let [cacheable-result (cacheable res)]
    (put cache spec params cacheable-result)
    cacheable-result))
