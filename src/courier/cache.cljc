(ns courier.cache
  (:require [clojure.string :as str]
            [courier.fingerprint :as fingerprint]))

(defprotocol Cache
  (lookup [_ k])
  (put [_ k res]))

(defn fname [f]
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
             (keyword (str/replace name #" " "-"))))))))

(defn cache-id [{:courier.http/keys [id req-fn]}]
  (or id
      (when req-fn (fname req-fn))
      :courier.http/req))

(defn- normalize-headers [headers]
  (->> headers
       (map (fn [[h v]] [(str/lower-case h) v]))
       (into {})))

(defn get-cache-relevant-params [{:courier.http/keys [id req-fn req]} params]
  (or (when id params)
      (when req-fn params)
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
    (lookup [_ k]
      (get @ref k))
    (put [_ k res]
      (swap! ref assoc k res))))

(comment
  (def backend (atom {}))
  (def cache (from-atom-map backend))

  (fingerprint/fingerprint
   (make-key cache {:courier.http/req {:method :get
                                       :headers {"Content-Type" "application/json"}
                                       :url "http://example.com"}}
             {}))

  (make-key cache {:courier.http/req {:method :get
                                      :url "http://example.com"}}
            {})
  )
