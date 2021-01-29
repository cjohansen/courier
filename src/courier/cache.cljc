(ns courier.cache
  (:require [clojure.string :as str]
            [courier.fs :as fs]
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

(defn cache-id [{:keys [lookup-id req-fn]}]
  (or lookup-id
      (when req-fn (fname req-fn))
      :courier.http/req))

(defn- normalize-headers [headers]
  (->> headers
       (map (fn [[h v]] [(str/lower-case h) v]))
       (into {})))

(defn get-cache-relevant-params [{:keys [lookup-id req-fn req]} params]
  (if (or lookup-id req-fn)
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
  (-> (select-keys result [:req :res :success?])
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

(defn str-id [spec]
  (let [id (cache-id spec)]
    (str (namespace id) "." (name id))))

(defn filename [dir spec params]
  (let [fingerprinted-name (fingerprint/fingerprint (get-cache-relevant-params spec params))
        [_ prefix postfix] (re-find #"(..)(.+)" fingerprinted-name)
        dirname (str dir "/" (str-id spec) "/" prefix)]
    (str dirname "/" postfix ".edn")))

(defn slurp-edn [file]
  (try
    (let [content (fs/read-file file)]
      (if-not (empty? content)
        #?(:clj (read-string content)
           :cljs (cljs.reader/read-string content))
        nil))
    (catch #?(:clj Throwable
              :cljs :default) e
      nil)))

(defn create-file-cache [{:keys [dir]}]
  (assert (string? dir) "Can't create file cache without directory")
  (fs/ensure-dir dir)
  (reify Cache
    (lookup [_ spec params]
      (when-let [file (filename dir spec params)]
        (when-let [val (slurp-edn file)]
          (if (expired? val)
            (do
              (fs/delete-file file)
              nil)
            val))))
    (put [_ spec params res]
      (when-let [file (filename dir spec params)]
        (fs/ensure-dir (fs/dirname file))
        (fs/write-file file (pr-str (assoc res ::file-name file)))
        {::file-name file}))))

(def ^:private carmine-available?
  "Carmine/Redis is an optional dependency, so we try to load it runtime. If the
  dependency is available, the redis cache can be used."
  (try
    (require 'taoensso.carmine)
    true
    (catch Throwable _ false)))

(defmacro wcar [& body]
  (when carmine-available?
    `(taoensso.carmine/wcar ~@body)))

(defn- redis-f [f & args]
  (apply (ns-resolve (symbol "taoensso.carmine") (symbol (name f))) args))

(defn redis-cache-key [spec params]
  (let [id (cache-id spec)
        params (get-cache-relevant-params spec params)]
    (->> [(namespace id)
          (name id)
          (fingerprint/fingerprint params)]
         (remove empty?)
         (str/join "/"))))

(defn create-redis-cache [conn-opts]
  (assert carmine-available? "com.taoensso/carmine needs to be on the classpath")
  (assert (not (nil? conn-opts)) "Please provide connection options")
  (reify Cache
    (lookup [_ spec params]
      (wcar conn-opts (redis-f :get (redis-cache-key spec params))))
    (put [_ spec params res]
      (let [ttl (- (time/millis (:expires-at res)) (time/millis (time/now)))
            cache-key (redis-cache-key spec params)]
        (wcar conn-opts (redis-f :psetex cache-key ttl (assoc res ::key cache-key)))
        {::key cache-key}))))
