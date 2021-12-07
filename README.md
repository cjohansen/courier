# Courier

Courier is a high-level HTTP client for Clojure and ClojureScript that improves
the robustness of your HTTP communications using API-specific information that
goes beyond the HTTP spec - "oh this API throws a 500 on every fifth request on
Sundays, just give it another try".

Courier offers:

- Caching
- Retries
- Inter-request dependencies

As an example, you can declare that a request requires an OAuth token, and
Courier will either find one in the cache, or make a separate request to fetch
one (**or** refresh the cached one if using it implies it's expired), making
sure to retry failures and handle all the nitty-gritty intricacies of this
interaction for you.

Courier's caching and retry mechanisms do not expect all the HTTP endpoints in
the world to be perfectly spec-compliant, and allows you to tune them to
out of band information about the APIs you're working with.

## Hello, Courier

At its most basic, you use Courier close to how you would use
[clj-http](https://github.com/dakrone/clj-http) or
[cljs-http](https://github.com/r0man/cljs-http) - in fact, it uses those two
libraries under the hood:

```clj
(require '[courier.http :as http])

(def res
  (http/request
   {:req {:method :get
          :url "http://example.com/api/demo"} ;; 1
    :retry-fn (http/retry-fn {:retries 2})})) ;; 2

(when (:success? res)
  (prn (:body res)))
```

1. The `:req` map is passed on to `clj-http` or `cljs-http`.
2. The request should be retried two times, if it fails _for any reason_:
   network errors, any non-2xx response. This defies the HTTP spec, but anyone
   who has used a few APIs in the wild know that they're not all 100% spec
   compliant. You can add nuance to this decision with
   `:retry-fn`, see below.

A slightly more involved example can better highlight Courier's strengths over
more low-level HTTP clients:

```clj
(require '[courier.http :as http]
         '[courier.cache :as courier-cache]
         '[clojure.core.cache :as cache])

(def spotify-token-request
  {:params [:client-id :client-secret] ;; 1

   :req-fn
   (fn [{:keys [client-id client-secret]}] ;; 2
     {:method :post
      :as :json
      :url "https://accounts.spotify.com/api/token"
      :form-params {:grant_type "client_credentials"}
      :basic-auth [client-id client-secret]})

   :retry-fn (http/retry-fn {:retries 2}) ;; 3

   :cache-fn (http/cache-fn
              {:ttl-fn #(-> % :res :body :expires_in (* 1000))})}) ;; 4

(def spotify-playlist-request
  {:params [:token :playlist-id]
   :lookup-params [:playlist-id] ;; 5

   :req-fn (fn [{:keys [token playlist-id]}]
             {:method :get
              :url (format "https://api.spotify.com/playlists/%s"
                           playlist-id)
              :oauth-token token})

   :retry-fn (http/retry-fn
              {:retries 2
               :refresh-fn #(when (-> % :res :status (= 401)) ;; 6
                              [:token])})

   :cache-fn (http/cache-fn {:ttl (* 10 1000)})}) ;; 7

(def cache (-> (atom (cache/lru-cache-factory {} :threshold 8192))) ;; 8
               (courier-cache/create-atom-map-cache)) ;; 9

(http/request ;; 10
 spotify-playlist-request
 {:cache cache
  :params {:client-id "my-api-client" ;; 11
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token {::http/req spotify-token-request ;; 12
                   ::http/select (comp :access_token :body)}}}) ;; 13
```

1. `:params` informs Courier of which parameters are required to make this
   request.
2. Specifying the details of the request with a function instead of an inline
   map allows us to defer and externalize details. The function will be passed
   the parameters named in `:params`.
3. Retry any failures up to two times.
4. Cache the response for as long as specified by the `:expires_in` key in the
   body of the request. Multiple the number of seconds with 1000.
5. `:lookup-params` determines what parameters are required to look for a
   previously cached response. Since the `:token` parameter is omitted from
   `:lookup-params`, the token request can be skipped completely when the
   playlist is cached.
6. When retrying a request, we can tell Courier to refresh some parameters. In
   this case, if the response was a 401, we will retry the request with a fresh
   token.
7. Cache playlists for a fixed 10 seconds.
8. Courier provides a caching protocol and comes with an implementation for
   atoms with map-like data structures, like the ones provided by
   `clojure.core.cache`.
9. Reifies the `courier.cache/Cache` protocol for an atom with a map-like data
    structure.
10. Make the request(s) and return the result of the playlist request.
11. Provide inline values for deferred parameters `:client-id` and
    `:client-secret`.
12. `:token` is provided as another request. If the playlist request is not
    cached, Courier will first request a token (including retries, checking for
    a cached token, etc), then request playlists. If the playlist request fails
    with a 401, Courier will fetch a new token and retry the playlist request.
13. When passing the result of the token request to the playlist request, pass
    `:access_token` from the response's `:body`. In other words, in the playlist
    request's `:req-fn`, `:token` will be the OAuth token string.

The `result` map returned from `http/request` contains `:status`, `:headers`,
and `:body`, just like a normal HTTP response map. Because a Courier request can
result in multiple request/response pairs (e.g. if retries are required), the
map also contains other keys, see [the result map](#the-result-map).

## Table of contents

- [Install](#install)
- [Parameters and dependencies](#parameters-and-dependencies)
- [The result map](#the-result-map)
- [Retries](#retry-on-failure)
- [Caching](#caching)
- [Events](#events)
- [Reference](#reference)
- [Changelog](#changelog)

## Install

Courier is a stable library - it will never change it's public API in breaking
way, and will never (intentionally) introduce other breaking changes.

With tools.deps:

```clj
cjohansen/courier {:mvn/version "2021.12.08"}
```

With Leiningen:

```clj
[cjohansen/courier "2021.12.08"]
```

**NB!** Please do not be alarmed if the version/date seems "old" - this just
means that no bugs have been discovered in a while. Courier is largely
feature-complete, and I expect to only rarely add to its feature set.

## Parameters and dependencies

Many HTTP APIs require authentication with an OAuth 2.0 token. This means we
first have to make an HTTP request for a token, then request the resource
itself. Courier allows you to explicitly model this dependency.

First, define the request for the token. To externalize credentials, provide a
function to `:req-fn`, and declare the function's dependencies with `:params`:

```clj
(def spotify-token-request
  {:params [:client-id :client-secret]
   :req-fn
   (fn [{:keys [client-id client-secret]}]
     {:url "https://accounts.spotify.com/api/token"
      :form-params {:grant_type "client_credentials"}
      :basic-auth [client-id client-secret]})})
```

Where do the params come from? You can pass them in as you make the request:

```clj
(require '[courier.http :as http])

(http/request
 spotify-token-request
 {:params {:client-id "username"
           :client-secret "password"}})
```

Then define a request that uses an oauth token:

```clj
(def spotify-playlist-request
  {:params [:token :playlist-id]
   :lookup-params [:playlist-id]
   :req-fn
   (fn [{:keys [token playlist-id]}]
     {:method :get
      :url (format "https://api.spotify.com/playlists/%s"
                   playlist-id)
      :oauth-token token})})
```

We _could_ manually piece the two together:

```clj
(require '[courier.http :as http])

(def token
  (http/request
   spotify-token-request
   {:params {:client-id "username"
             :client-secret "password"}}))

(http/request
 spotify-playlist-request
 {:params {:playlist-id "4abdc"
           :token (:access_token (:body token))}})
```

Even better, let Courier manage the dependency:

```clj
(require '[courier.http :as http])

(http/request
 spotify-playlist-request
 {:params {:client-id "username"
           :client-secret "password"
           :playlist-id "4abdc"
           :token {::http/req spotify-token-request
                   ::http/select (comp :access_token :body)}}})

```

When Courier knows about the dependency, it can provide a higher level of
service, especially if we also give it a means to cache results:

- If requesting the token fails for some reason, retry it before requesting the
  playlist resource
- Don't request a new token if we have one in the cache

Additionally, if the cached token expires and the playlist resource fails with a
401, Courier can automatically request a new token and retry the playlist
resource with it:

```clj
(def spotify-playlist-request
  {:params [:token :playlist-id]
   :lookup-params [:playlist-id]
   :req-fn
   (fn [{:keys [token playlist-id]}]
     {:method :get
      :url (format "https://api.spotify.com/playlists/%s"
                   playlist-id)
      :oauth-token token})
   :retry-fn (http/retry-fn
              {:retries 3
               :refresh-fn (fn [{:keys [req res]}]
                             (when (= 401 (:status res))
                               [:token]))})})
```

## The result map

The map returned by Courier contains the resulting data if successful, along
with information about all requests leading up to it. It contains the following
keys:

- `:success?` - A boolean
- `:log` - A list of maps describing each attempt
- `:cache-status` - A map describing the cache status of the data
- `:status` - The response status of the last response
- `:headers` - The headers on the last response
- `:body` - The body of the last response

The `:log` list contains maps with the following keys:

- `:req` - The request map
- `:res` - The full response
- `:retry` - The result of the `:retry-fn`, if set
- `:cache` - The result of the `:cache-fn`, if set
- `:event` - The courier event, one of
  - `:courier.http/response`
  - `:courier.http/cache-hit`
  - `:courier.http/failed`

`:retry` and `:cache` are only available when relevant.

The `:cache-status` map contains the folowing keys:

- `:cache-hit?` - A boolean, `true` if the result was pulled from the cache
- `:stored-in-cache?` - A boolean, `true` if the result was stored in the cache
- `:cached-at` - A timestamp (epoch milliseconds) when the object was cached
- `:expires-at` - A timestamp (epoch milliseconds) when the object expires from
  the cache.

Specific cache implementations may add additional keys in this map, with further
details about the cache entry, see individual implementations.

## Retry on failure

HTTP requests can fail for any number of reasons. Sometimes problems go away if
you try again. By default, Courier will consider any `GET` request retryable so
long as you specify a number of retries:

```clj
(require '[courier.http :as http])

(http/request
 {:req {:method :get
        :url "http://example.com/api/demo"}
  :retry-fn (http/retry-fn {:retries 2})})
```

With this addition, the request will be retried 2 times before causing an
error - _if the result can be retried_. As mentioned, Courier considers any
`GET` request retryable. If you want more fine-grained control over this
decision, pass a function with the `:retryable?` keyword:

```clj
(require '[courier.http :as http])

(http/request
 {:req {:method :get
        :url "http://example.com/api/demo"}
  :retry-fn (http/retry-fn
             {:retries 2
              :retryable? #(-> % :req :method (= :get))})})
```

The function is passed a map with both `:req` and `:res` to help inform its
decision. If this function returns `false`, the request will not be retried even
if all the `:retries` haven't been exhausted.

### When to retry?

By default Courier will retry failing requests immediately. If desired, you can
insert a pause between retries:

```clj
(require '[courier.http :as http])

(http/request
 {:req {:method :get
        :url "http://example.com/api/demo"}
  :retry-fn (http/retry-fn
             {:retries 5
              :retryable? #(= :get (-> % :req :method))
              :delays [100 250 500]})})
```

This will cause the first retry to happen 100ms after the initial request, the
second 250ms after the first, and the remaining ones will be spaced out by
500ms. If you want the same delay between each retry, specify a vector with a
single number: `[100]`.

### What is a failure?

By default, Courier leans on the underlying http client library to determine if
a response is a success or not. In other words, anything with a 2xx response
status is a success, everything else is a failure. If this does not agree with
the reality of your particular service, you can provide a custom function to
determine success:

```clj
(require '[courier.http :as http])

(http/request
 {:req {:method :get
        :url "http://example.com/api/demo"}
  :success? #(= 200 (-> % :res :status))})
```

### Retries with refreshed dependencies

If you are using [caching](#caching), it might not be worth retrying a fetch
with the same (possibly stale) set of dependencies - you might need to refresh
some or all of them. To continue the example of the authentication token, a 401
response from a service could be worth retrying, but only with a fresh token.

`:refresh-fn` takes a function that is passed a map of `:req` and `:res`, and
can return a vector of parameters that should be refreshed before retrying this
one:

```clj
(require '[courier.http :as http])

(def spotify-playlist-request
  {:params [:token :playlist-id]
   :req-fn (fn [{:keys [token playlist-id]}]
             {:method :get
              :url (format "https://api.spotify.com/playlists/%s"
                           playlist-id)
              :oauth-token token})
   :retry-fn (http/retry-fn
              {:retries 2
               :refresh-fn #(when (= 401 (-> % :res :status))
                              [:token])})})
```

If the response to this request is an HTTP 401, Courier will grab a new `:token`
by refreshing that request (bypassing the cache) and then try again. This
naturally requires that the `:token` param is passed as a request map, like here:

```clj
(http/request
 spotify-playlist-request
 {:cache cache
  :params {:client-id "my-api-client"
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token {::http/req spotify-token-request
                   ::http/select (comp :access_token :body)}}})
```

If the `:token` param is instead passed as a literal, Courier cannot refresh it
and the request will fail.

## Caching

Courier caching is provided by the `courier.cache/Cache` protocol, which defines
the following two functions:

```clj
(defprotocol Cache
  (lookup [_ spec params])
  (put [_ spec params res]))
```

`spec` is the full map passed to `courier.http/request`. `params` is a map of
all the lookup params - this would be the keys named in `:lookup-params`, if
set, or `:params`. If neither of these are available, `params` will be empty.

`lookup` attempts to load a cached response for the request. If this function
returns a non-nil value, it should be a map of `{req, res}`, and `put` will
never be called.

If the value does not exist in the cache, the request will be made, and if
successful, `put` will be called with the result.

A reified instance of a cache can be passed to `http/request` as `:cache`:

```clj
(require '[courier.http :as http]
         '[courier.cache :as courier-cache]
         '[clojure.core.cache :as cache])

(def cache-atom (atom (cache/lru-cache-factory {} :threshold 8192))) ;; def for inspection
(def cache (courier-cache/create-atom-map-cache cache-atom))

(http/request
 spotify-playlist-request
 {:cache cache
  :params {:client-id "my-api-client"
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token {::http/req spotify-token-request
                   ::http/select (comp :access_token :body)}}})
```

### Lookup params

Lookup params can be used in place of the full request to make more efficient
use of the cache. Consider the playlist request from before:

```clj
(def spotify-playlist-request
  {:params [:token :playlist-id]
   :req-fn (fn [{:keys [token playlist-id]}]
             {:method :get
              :url (format "https://api.spotify.com/playlists/%s"
                           playlist-id)
              :oauth-token token})
   :cache-fn (http/cache-fn {:ttl (* 10 1000)})})
```

When the `:token` parameter is provided by another request, Courier might have
to request a token only to find a cached version of the playlist in the cache.
If the playlist is already cached, there is no need for a token. Constructing a
cache key from the `:lookup-params` only, Courier will skip the token request if
the playlist is cached:

```clj
(def spotify-playlist-request
  {:params [:token :playlist-id]
   :lookup-params [:playlist-id]
   :req-fn (fn [{:keys [token playlist-id]}]
             {:method :get
              :url (format "https://api.spotify.com/playlists/%s"
                           playlist-id)
              :oauth-token token})
   :cache-fn (http/cache-fn {:ttl (* 10 1000)})})
```

With `:lookup-params` in place, `courier.cache/lookup` won't receive the full
request, only the spec and the cache parameters (the playlist ID). The `:req-fn`
can be used to identify the request, but it usually won't do so in a
human-friendly manner. A better approach is to include `:lookup-id` in the cache
spec. `courier.cache/cache-key` can use this to construct a short,
human-friendly cache key:

```clj
(def spotify-playlist-request
  {:params [:token :playlist-id]
   :lookup-params [:playlist-id]
   :lookup-id :spotify-playlist-request
   :req-fn (fn [{:keys [token playlist-id]}]
             {:method :get
              :url (format "https://api.spotify.com/playlists/%s"
                           playlist-id)
              :oauth-token token})
   :cache-fn (http/cache-fn {:ttl (* 10 1000)})})
```

With this spec, the "atom map" cache mentioned earlier will cache a request for
the playlist with id `"3b5045a0-05fc-4d7f-8b61-9c6d37ab90e6"` under the
following key:

```clj
(def cache-key
  [:spotify-playlist-request
   {:playlist-id "3b5045a0-05fc-4d7f-8b61-9c6d37ab90e6"}])

(get @cache-atom cache-key) ;; Playlist response
```

#### Surgical lookup params

Sometimes your requests will use unwieldy data structures like configuration
maps as parameters. This could lead to very large cache keys, or worse -
sensitive data like credentials being used as cache keys. To avoid this, a
lookup param can be expressed as a vector, which will be used to `get-in` the
named parameter.

Let's parameterize the Spotify API host using a configuration map:

```clj
(def spotify-playlist-request
  {:lookup-id :spotify-playlist-request
   :params [:token :config :playlist-id]
   :req-fn (fn [{:keys [token config playlist-id]}]
             {:method :get
              :url (format "https://%s/playlists/%s"
                           (:spotify-host config)
                           playlist-id)
              :oauth-token token})
   :cache-fn (http/cache-fn {:ttl (* 10 1000)})})
```

In order to include only the relevant key in the cache key,
`:lookup-params` can be expressed like so:

```clj
(def spotify-playlist-request
  {:lookup-id :spotify-playlist-request
   :params [:token :config :playlist-id]
   :lookup-params [[:config :spotify-host] :playlist-id]
   :req-fn (fn [{:keys [token config playlist-id]}]
             {:method :get
              :url (format "https://%s/playlists/%s"
                           (:spotify-host config)
                           playlist-id)
              :oauth-token token})
   :cache-fn (http/cache-fn {:ttl (* 10 1000)})})
```

Which will result in the following cache key for the atom map caches:

```clj
(def cache-key
  [:spotify-playlist-request
   {:config {:spotify-host "api.spotify.com"}
    :playlist-id "3b5045a0-05fc-4d7f-8b61-9c6d37ab90e6"}])
```

#### Manipulating lookup params

Some endpoints do not take any identifying parameters other than the token, and
returns content belonging to the user for whom the token is issued. If the token
contains information that's stable across tokens, you can pass the lookup
parameters through a transforming function before looking up the value in the
cache. In this case you will always need a token, but maybe you won't need to
make the data request over again.

Let's fetch all the playlists belonging to a user. This resource only uses the
token to identify the user.

```clj
(def spotify-playlists-request
  {:lookup-id :spotify-playlists
   :params [:token :config]
   :lookup-params [:token [:config :spotify-host]]
   :req-fn (fn [{:keys [token config]}]
             {:method :get
              :url (format "https://%s/playlists/"
                           (:spotify-host config))
              :oauth-token token})
   :cache-fn (http/cache-fn {:ttl (* 10 1000)})})
```

This caches with the token, which is no good. We can add
`:prepare-lookup-params` to extract only the relevant bits:

```clj
(defn base64-decode [s]
  (.decode (java.util.Base64/getDecoder) s))

(defn decode-jwt [token]
  (-> (clojure.string/split token #"\.")
      second
      base64-decode
      String.
      (cheshire.core/parse-string keyword)))

(def spotify-playlists-request
  {:lookup-id :spotify-playlists
   :params [:token :config]
   :lookup-params [:token :config] ;; *)
   :prepare-lookup-params (fn [params]
                            {:host (get-in params [:config :spotify-host])
                             :user-id (:userId (decode-jwt (:token params)))})
   :req-fn (fn [{:keys [token config]}]
             {:method :get
              :url (format "https://%s/playlists/"
                           (:spotify-host config))
              :oauth-token token})
   :cache-fn (http/cache-fn {:ttl (* 10 1000)})})
```

Which will result in the following cache key for the atom map caches:

```clj
(def cache-key
  [:spotify-playlists
   {:host "api.spotify.com"
    :user-id "3b5045a0-05fc-4d7f-8b61-9c6d37ab90e6"}])
```

*) The `:lookup-params` are still needed to let Courier know which parameters
must be realized before calling the `:prepare-lookup-params` function.

### Atom map cache

The atom map cache gives you a quick and easy in-memory cache for your HTTP
requests. Stick a map, or a map-like data structure, in an atom, and off you go.
[clojure.core.cache](https://github.com/clojure/core.cache) has lots of nice
caches that go well with this Courier cache:

```clj
(require '[courier.http :as http]
         '[courier.cache :refer [create-atom-map-cache]]
         '[clojure.core.cache :as cache])

(def cache (create-atom-map-cache
            (atom (cache/lru-cache-factory {} :threshold 8192))))

(http/request
 spotify-playlist-request
 {:cache cache
  :params {:client-id "my-api-client"
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token {::http/req spotify-token-request
                   ::http/select (comp :access_token :body)}}})
```

The atom map cache adds a `:courier.cache/cache-key` to the `:cache-status` map,
indicating the key under which the result is stored in the cache.

### File cache

The file cache stores responses on disk. Give it a directory, and off you go.

```clj
(require '[courier.http :as http]
         '[courier.cache :as cache])

(def file-cache (cache/create-file-cache {:dir "/tmp/courier"}))

(http/request
 spotify-playlist-request
 {:cache file-cache
  :params {:client-id "my-api-client"
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token {::http/req spotify-token-request
                   ::http/select (comp :access_token :body)}}})
```

The file cache adds a `:courier.cache/file-name` key to the `:cache-status` map,
containing the full path on disk to the file storing the cached response.
Cache files are stored in files with UUID names, sharded by the first two
characters, to avoid too many files in a single directory.

### Redis cache

To cache Courier responses in Redis you must "bring your own"
[Carmine](https://github.com/ptaoussanis/carmine):

```clj
com.taoensso/carmine {:mvn/version "3.1.0"}
```

Then create a cache with a pool spec:

```clj
(require '[courier.http :as http]
         '[courier.cache :as cache]
         '[taoensso.carmine.connections :as cc])

(def pool-spec
  (let [conn-spec {:spec {:uri "redis://localhost"}}
        [pool conn] (cc/pooled-conn conn-spec)
        pool-spec (assoc conn-spec :pool pool)]
    (.release-conn pool conn)
    pool-spec))

(def redis-cache (cache/create-redis-cache pool-spec))

(http/request
 spotify-playlist-request
 {:cache redis-cache
  :params {:client-id "my-api-client"
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token {::http/req spotify-token-request
                   ::http/select (comp :access_token :body)}}})
```

## Events

Even though `(courier.http/request spec)` looks like a single request, it can
spawn multiple requests to several endpoints. Most of the time we're only
interested in the end result, in which case `request` is just what the doctor
ordered.

Sometimes you want more insight into the network layer of your application.
Maybe you want to log each request on the way out and the response coming back.
Courier does all its heavy lifting with `courier.http/make-requests`, but there
is another medium-level abstraction on top of it: `request-with-log`. This
function works just like `request`, except it also gives you a `core.async`
channel that emits events as they occur:

```clj
(require '[courier.http :as http]
         '[clojure.core.async :as a])

(let [[log-ch result-ch]
      (http/request-with-log
       spotify-playlist-request
       {:cache cache
        :params {:client-id "my-api-client"
                 :client-secret "api-secret"
                 :playlist-id "3abdc"
                 :token {::http/req spotify-token-request
                         ::http/select (comp :access_token :body)}}})]

  ;; The result channel emits the full result, as returned by `request`:
  (a/go (a/<! result-ch))

  ;; The events channel gives you realtime insight into the ongoing process:
  (a/go-loop []
    (when-let [event (a/<! log-ch)]
      (case (:event event)
        ::http/request
        (log/info "Request" (:req event))

        ::http/response
        (log/info "Response"
                  (:method (:req event))
                  (:url (:req event))
                  (select-keys (:res event) [:status
                                             :headers
                                             :body
                                             :request-time]))

        ::http/store-in-cache
        (log/info "Cache response" (select-keys event [:req :res]))

        ::http/cache-hit
        (log/info "Cache hit" (select-keys event [:req :res]))

        ::http/exception
        (log/error event)

        ::http/failure
        (log/error "Failed to complete request" event))
      (recur)))
```

The `log-ch` is closed when the request is complete.

## Testing

Courier runs all requests through a multi-method that you can override for
testing purposes:

```clj
(require '[courier.client :as client]
         '[courier.http :as http])

(defmethod client/request [:get "http://example.com"] [req]
  {:status 200
   :headers {"content-type" "application/json"}
   :body {:ok? true}})

(:body (http/request {:req {:url "http://example.com"}}))
;;=> {:ok? true}

```

## Reference

### `(courier.http/request spec opt)`

`spec` is a map of the following keys:

- `:req` - Inline request map
- `:req-fn` - A function that computes the request map. Will be called with the
  parameters named by the `:params` key.
- `:params` - The parameters to pass to `:req-fn`. This may contain references
  to other requests - if it does those will be resolved before `:req-fn` is
  called and this request is carried out.
- `:lookup-params` - The parameters required to look this request up in the
  cache. Specifying this has two benefits: avoid using sensitive values like
  credentials as cache keys, and avoid making dependent requests if a cached
  response is available.
- `:success?` - A function that is passed a map of `{:req :res}` and that
  returns a boolean indicating if the response was a success. The default
  implementation returns `true` for any 2XX response.
- `:retry-fn` - A function that is called if the response is not a success. It
  is passed a map of `{:req :res :num-attempts}` (the latter being the number of
  attempts already made at this request) and should return a map describing if
  and how the request may be retried, as described by the keys:
  - `:retry?` - If `true`, the request will be retried
  - `:delay` - A number of milliseconds to wait before trying again, optional.
  - `:refresh` - A list of `:params` that should be fetched anew, bypassing the
    cache, before trying this request again.
- `:cache-fn` - A function that is called if the response is a success. It is
  passed a map of `{:req :res}` and should return a map describing if and how
  the response may be cached, as described by the keys:
  - `:cache?` - If `true`, the response will be cached _if expires-at is specified_.
  - `:expires-at` - An epoch millis at which the response expires from the cache.

### `(courier.http/cache-fn {:ttl :ttl-fn :cacheable?})`

Returns a function that can be passed as `:cache-fn` to `courier.http/request`.
Either set `:ttl` to a number of milliseconds to cache results, or set `:ttl-fn`
to a function that will return the number of milliseconds. If set, it will be
passed a map of `:req` and `:res` to aid in the decision.

If you only want to cache some request/response pairs, pass a function to
`:cacheable?` which takes a map of `:req` and `:res` and returns `true` if the
result is cacheable.

## Changelog

### 2021.12.08

Specifically handle connection and timeout exceptions, and return an error with
`:courier.error/reason` set to one of the following keywords, rather than relay
the entire exception object:

- `:courier.error/connection-refused`
- `:courier.error/socket-timeout`
- `:courier.error/connection-timeout`

These are reported in addition to the previously added
`:courier.error/unknown-host`.

### 2021.09.17

Fail gracefully with a dedicated error keyword when the `:req-fn` throws an
exception.

### 2021.07.13

Fail gracefully with a dedicated error keyword when trying to make requests
without both a `:req` map and a `:req-fn` IFn.

### 2021.02.22

Failing requests now carry all the normal response keys directly on the result.
Previously, a failed request, or a request that failed after a series of retries
would only include the response in `(:courier.error/data res)`. With this
change, the `:success?`, `:status`, `:headers`, and `:body` keys are available
directly on the result, just like with successful results. The `courier.error`
keys are still present, and contain the same data as before.

### 2021.01.26

Specifically handle unknown host exceptions to make it clearer why a request
fails.

Do not report failed responses as "retries exhausted" when there was no
retries - report as failed request instead.

Include the last response on failures due to exhausted retries.

### 2021.01.20

Fix bug where `:prepare-lookup-params` was called before all lookup params was
available.

Include cache retrieval events in the `:log` in meta data returned from
`request`. Also include the event name on each entry in the log.

### 2021.01.19

Added support for `:prepare-lookup-params`, which allows for transforming the
lookup parameters before using them to store and retrieve items from the cache.

Fix a bug where `POST` requests where not cached by default when `:cache-fn` was
provided.

### 2020.12.12

Initial release to Clojars (after being battle-tested in production as a git
dependency).

## Acknowledgements

This library is my second attempt at building a more robust tool for HTTP
requests in Clojure. It is a smaller and more focused version of
[Pharmacist](https://github.com/cjohansen/pharmacist), which I now consider a a
flawed execution of a good idea. Courier is based on a bunch of helper functions
I wrote for using Pharmacist primarily for HTTP requests. It attempts to present
the most useful aspects of Pharmacist in a much less ceremonious API that is
closer to traditional low-level HTTP libraries.

As always, [Magnar Sveen](https://github.com/magnars) has been an important
contributor to the design of the API.

## License

Copyright © 2020-2021 Christian Johansen

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
