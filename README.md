# Courier

Courier is a high-level HTTP client for Clojure and ClojureScript that improves
the robustness of your HTTP communications using API-specific information that
goes beyond the HTTP spec - "oh this API throws a 500 on every fifth request on
Sundays, just give it another try".

Courier offers caching and retry mechanics, inter-request dependencies ("get the
OAuth token from cache, or fetch it from this API before hitting this
endpoint"), and deferred parameterization - all of which can be tuned to a
priori information about individual endpoints.

## Hello, Courier

At its most basic, you use Courier close to how you would use
[clj-http](https://github.com/dakrone/clj-http) or
[cljs-http](https://github.com/r0man/cljs-http) - in fact, it uses those two
libraries under the hood:

```clj
(require '[courier.http :as http])

(def res
  (http/request
   {::http/request {:method :get
                    :url "http://example.com/api/demo"} ;; 1
    ::http/retries 2})) ;; 2

(when (:courier.res/success? result)
  (prn (:courier.res/data res))) ;; Prints the response body
```

1. The `::http/request` map is passed on to `clj-http` or `cljs-http`.
2. The request should be retried two times, if it fails _for any reason_:
   network errors, any non-2xx response. This defies the HTTP spec, but anyone
   who has used a few APIs in the wild know that they're not all 100% spec
   compliant. You can add nuance to this decision with
   `:courier.http/retryable?`, see below.

A slightly more involved example can better highlight Courier's strengths over
more low-level HTTP clients:

```clj
(require '[courier.http :as http]
         '[courier.cache :as courier-cache]
         '[clojure.core.cache :as cache])

(def spotify-token
  {::http/request-fn
   (fn [{:keys [client-id client-secret]}] ;; 1
     {:method :post
      :url "https://accounts.spotify.com/api/token"
      :form-params {:grant_type "client_credentials"}
      :basic-auth [client-id client-secret]})
   ::http/params [:client-id :client-secret] ;; 2
   ::http/retries 2 ;; 3
   ::http/cache-params [:client-id] ;; 4
   ::http/cache-for-fn #(* 1000 (-> % :body :expires_in))}) ;; 5

(def spotify-playlist
  {::http/params [:token :playlist-id]
   ::http/request-fn (fn [{:keys [token playlist-id]}]
                       {:method :get
                        :url (format "https://api.spotify.com/playlists/%s"
                                     playlist-id)
                        :oauth-token (:access_token token)})
   ::http/retries 2
   ::http/cache-for (* 10 1000) ;; 6
   ::http/retry-refresh-fn #(when (= 403 (-> % :res :status)) ;; 7
                              [:token])})

(def cache (atom (cache/lru-cache-factory {} :threshold 8192))) ;; 8

(http/request ;; 9
 spotify-playlist
 {:cache (courier-cache/from-atom-map cache) ;; 10
  :params {:client-id "my-api-client" ;; 11
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token spotify-token}}) ;; 12
```

1. Specifying the details of the request with a function instead of an inline
   map allows us to defer and externalize details.
2. `::http/params` informs Courier of which parameters are required to make this
   request.
3. Retry any failures up to two times.
4. Only use the `client-id` to cache this the response.
5. Cache the response for as long as specified by the `:expires_in` key in the
   body of the request. Multiple the number of seconds with 1000.
6. Cache playlists for 10 seconds.
7. When this request needs to be retried, we can tell Courier to refresh some
   parameters. In this case, if the response was a 403, we will retry the
   request with a fresh token.
8. Courier provides a caching protocol and comes with an implementation for
   atoms with map-like data structures, like the ones provided by
   `clojure.core.cache`.
9. Make the request and return the result of the playlist request.
10. Reifies the `courier.cache/Cache` protocol for an atom with a map-like data
    structure.
11. Provide inline values for deferred parameters `:client-id` and
    `:client-secret`.
12. `:token` is provided as another request. If the playlist request is not
    cached, Courier will first request a token (including retries, checking for
    a cached token, etc), then request playlists. If the playlist request fails
    with a 403, Courier will fetch a new token and retry the playlist request.

The `result` map returned from `http/request` differs from an HTTP response. A
Courier request can result in multiple request/response pairs (e.g. if retries
are required), and the resulting map contains all information about the result
and all attempts at acquiring it. `:courier.res/data` contains the body of the
response. How `:courier.res/data` is populated from the response can be
controlled, see `:courier.http/data-fn` below.

## Table of contents

- [Install](#install)
- [Parameters and dependencies](#parameters-and-dependencies)
- [The result map](#the-result-map)
- [Retries](#retry-on-failure)
- [Caching](#caching)
- [Events](#events)
- [Changelog](#changelog)

## Install

**NB!** The goal is for Courier to become a stable library worthy of your trust,
which never intentionally breaks backwards compatibility. Currently, however, it
is still under development/being designed, and breaking changes should be
expected. This will be the case for as long as the version is prefixed with a
`0`.

With tools.deps:

```clj
cjohansen/courier {:mvn/version "0.2020.11.13"}
```

With Leiningen:

```clj
[cjohansen/courier "0.2020.11.13"]
```

## Parameters and dependencies

Many HTTP APIs require authentication with an OAuth 2.0 token. This means we
first have to make an HTTP request for a token, then request the resource
itself. Courier allows you to explicitly model this dependency.

First, define the request for the token. To externalize credentials, provide a
function to `:courier.http/request-fn`, and declare the function's dependencies
with `:courier.http/params`:

```clj
(def spotify-token
  {:courier.http/params [:client-id :client-secret]
   :courier.http/request-fn
   (fn [{:keys [client-id client-secret]}]
     {:url "https://accounts.spotify.com/api/token"
      :form-params {:grant_type "client_credentials"}
      :basic-auth [client-id client-secret]})})
```

Where do the params come from? You can pass them in as you make the request:

```clj
(require '[courier.http :as http])

(http/request
 spotify-token
 {:params {:client-id "username"
           :client-secret "password"}})
```

Then define a request that uses an oauth token:

```clj
(def spotify-playlist
  {:courier.http/params [:token :playlist-id]
   :courier.http/request-fn
   (fn [{:keys [token playlist-id]}]
     {:method :get
      :url (format "https://api.spotify.com/playlists/%s"
                   playlist-id)
      :oauth-token (:access_token token)})})
```

We _could_ manually piece the two together:

```clj
(require '[courier.http :as http])

(def token
  (http/request
   spotify-token
   {:params {:client-id "username"
             :client-secret "password"
             :playlist-id "4abdc"}}))

(http/request
 spotify-playlist
 {:context {:token (:courier.res/data token)}})
```

Even better, let Courier manage the dependency:

```clj
(require '[courier.http :as http])

(http/request
 spotify-playlist
 {:params {:token spotify-token}})
```

When Courier knows about the dependency, it can provide a higher level of
service, especially if we also give it a means to cache results:

- If requesting the token fails for some reason, retry it before requesting the
  playlist resource
- Don't request a new token if we have one in the cache
- If the cached token expires and the playlist resource fails with a 401,
  request a new token and retry the playlist resource with it

## The result map

The map returned by Courier contains the resulting data if successful, along
with information about all requests leading up to it. It contains the following
keys:

- `:courier.res/success?` - A boolean
- `:courier.res/data` - The resulting data, if successful
- `:courier.res/log` - A list of maps describing each attempt
- `:courier.res/cache-status` - A map describing the cache status of the data (TODO)
- `:status` - The response status of the last response
- `:headers` - The headers on the last response
- `:body` - The body of the last response

The `:courier.res/log` list contains maps with the following keys:

- `:req` - The request map
- `:res` - The full response
- `:retryable?` - A boolean
- `:cacheable?` - A boolean

The `:courier.res/cache-status` map contains the folowing keys:

- `:cached?` - A boolean
- `:cached-at` - A timestamp (epoch milliseconds) when the object was cached
- `:expires-at` - A timestamp (epoch milliseconds) when the object expires from
  the cache.

### Preparing result data

A successful request will include the response body as `:courier.res/data`. When
using a request as a parameter to another request, it is the data under
`:courier.res/data` that will be passed. If the unprocessed body of the response
isn't enough, you can compile the data yourself, using `:courier.http/data-fn`,
which will receive both the response:

```clj
(require '[courier.http :as http])

(http/request
 {::http/request {:method :get
                  :url "http://example.com/api/demo"}
  ::http/data-fn (fn [res]
                   (-> res :body :result)])})
```

## Retry on failure

HTTP requests can fail for any number of reasons. Sometimes problems go away if
you try again. By default, Courier will consider any `GET` request retryable so
long as you specify a number of retries on your request:

```clj
(require '[courier.http :as http])

(http/request
 {::http/request {:method :get
                  :url "http://example.com/api/demo"}
  ::http/retries 2})
```

With this addition, the request will be retried 3 times before causing an
error - _if the result can be retried_. As mentioned, Courier considers any
`GET` request retryable. If you want more fine-grained control over this
decision, pass a function with the `:courier.http/retryable?` keyword:

```clj
(require '[courier.http :as http])

(http/request
 {::http/request {:method :get
                  :url "http://example.com/api/demo"}
  ::http/retryable? #(= :get (-> % :req :method))
  ::http/retries 2})
```

The function is passed a map with both `:req` and `:res` to help inform its
decision. If this function returns false, the request will not be retried even
if all the `::http/retries` haven't been exhausted.

### When to retry?

By default Courier will retry failing requests immediately. If desired, you can
insert a pause between retries:

```clj
(require '[courier.http :as http])

(http/request
 {::http/request {:method :get
                  :url "http://example.com/api/demo"}
  ::http/retryable? #(= :get (-> % :req :method))
  ::http/retry-delays [100 250 500]
  ::http/retries 5})
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
 {::http/request {:method :get
                  :url "http://example.com/api/demo"}
  ::http/success? #(= 200 (:status %))})
```

### Retries with refreshed dependencies

If you are using [caching](#caching), it might not be worth retrying a fetch
with the same (possibly stale) set of dependencies - you might need to refresh
some or all of them. To continue the example of the authentication token, a 403
response from a service could be worth retrying, but only with a fresh token.

`:courier.http/retry-refresh-fn` takes a function that is passed a map of `:req`
and `:res`, and can return a vector of parameters that should be refreshed
before retrying this one:

```clj
(require '[courier.http :as http])

(def spotify-playlist
  {::http/params [:token :playlist-id]
   ::http/request-fn (fn [{:keys [token playlist-id]}]
                       {:method :get
                        :url (format "https://api.spotify.com/playlists/%s"
                                     playlist-id)
                        :oauth-token (:access_token token)})
   ::http/retries 2
   ::http/retry-refresh-fn #(when (= 403 (-> % :res :status))
                              [:token])})
```

If the response to this request is an HTTP 403, Courier will grab a new `:token`
by refreshing that request (bypassing the cache) and then try again.

## Caching

Courier caching is provided by the `courier.cache/Cache` protocol, which defines
the following two functions:

```clj
(defprotocol Cache
  (lookup [_ spec params])
  (set [_ spec params res]))
```

`spec` is the full map passed to `courier.http/request`. `params` is a map of
all the cache params - this would be the keys named in
`:courier.http/cache-params`, if set, or `:courier.http/params` - if neither of
these are available, `params` will be empty.

`lookup` attempts to load a cached response for the request. If this function
returns a non-nil value, it should be a map of `{req, res, path}`, and `put`
will never be called.

If the value does not exist in the cache, the request will be made, and if
successful, `put` will be called with the result.

A reified instance of a cache can be passed to `http/request` as `:cache`:

```clj
(require '[courier.http :as http]
         '[courier.cache :as courier-cache]
         '[clojure.core.cache :as cache])

(def cache (atom (cache/lru-cache-factory {} :threshold 8192)))

(http/request
 spotify-playlist
 {:cache (courier-cache/from-atom-map cache)
  :params {:client-id "my-api-client"
           :client-secret "api-secret"
           :playlist-id "3abdc"
           :token spotify-token}})
```

### Cache params

Cache params can be used in place of the full request to make more efficient use
of the cache. Consider the playlist request from before:

```clj
(def spotify-playlist
  {:courier.http/params [:token :playlist-id]
   :courier.http/request-fn (fn [{:keys [token playlist-id]}]
                              {:method :get
                               :url (format "https://api.spotify.com/playlists/%s"
                                            playlist-id)
                               :oauth-token (:access_token token)})
   :courier.http/cache-for (* 10 1000)})
```

If the `:token` parameter is provided by another request, Courier might have to
request a token only to find a cached version of the playlist in the cache. If
the playlist is already cached, there is no need for a token. Constructing a
cache key from the `:courier.http/cache-params` only, Courier will omit the token
request if the playlist is cached:

```clj
(def spotify-playlist
  {:courier.http/params [:token :playlist-id]
   :courier.http/cache-params [:playlist-id]
   :courier.http/request-fn (fn [{:keys [token playlist-id]}]
                              {:method :get
                               :url (format "https://api.spotify.com/playlists/%s"
                                            playlist-id)
                               :oauth-token (:access_token token)})
   :courier.http/cache-for (* 10 1000)})
```

With `:courier.http/cache-params` in place, `courier.cache/lookup` won't receive
the full request, only the spec and the cache parameters (the playlist ID). The
`:courier.http/request-fn` can be used to identify the request, but it usually
won't do so in a human-friendly manner. A better approach is to include
`:courier.http/id` in the spec. `courier.cache/cache-key` can use this to
construct a short, human-friendly cache key:

```clj
(def spotify-playlist
  {:courier.http/id :spotify-playlist
   :courier.http/params [:token :playlist-id]
   :courier.http/cache-params [:playlist-id]
   :courier.http/request-fn (fn [{:keys [token playlist-id]}]
                              {:method :get
                               :url (format "https://api.spotify.com/playlists/%s"
                                            playlist-id)
                               :oauth-token (:access_token token)})
   :courier.http/cache-for (* 10 1000)})
```

With this spec, the "atom map" cache mentioned earlier will cache a request for
the playlist with id `"3b5045a0-05fc-4d7f-8b61-9c6d37ab90e6"` under the
following key:

```clj
(def cache-key
  [:spotify-playlist
   {:playlist-id "3b5045a0-05fc-4d7f-8b61-9c6d37ab90e6"}])

(get @cache cache-key) ;; Playlist response
```

Sometimes your requests will use unwieldy data structures like configuration
maps as parameters. This could lead to very large cache keys, or worse -
sensitive data like credentials being used as cache keys. To avoid this, a cache
param can be expressed as a vector, which will be used to `get-in` the named
parameter.

Let's parameterize the Spotify API host using a configuration map:

```clj
(def spotify-playlist
  {:courier.http/id :spotify-playlist
   :courier.http/params [:token :config :playlist-id]
   :courier.http/request-fn (fn [{:keys [token config playlist-id]}]
                              {:method :get
                               :url (format "https://%s/playlists/%s"
                                            (:spotify-host config)
                                            playlist-id)
                               :oauth-token (:access_token token)})
   :courier.http/cache-for (* 10 1000)})
```

In order to include only the relevant key in the cache key,
`:courier.http/cache-params` can be expressed like so:

```clj
(def spotify-playlist
  {:courier.http/id :spotify-playlist
   :courier.http/params [:token :config :playlist-id]
   :courier.http/cache-params [[:config :spotify-host] :playlist-id]
   :courier.http/request-fn (fn [{:keys [token config playlist-id]}]
                              {:method :get
                               :url (format "https://%s/playlists/%s"
                                            (:spotify-host config)
                                            playlist-id)
                               :oauth-token (:access_token token)})
   :courier.http/cache-for (* 10 1000)})
```

Which will result in the following cache key for the atom map caches:

```clj
(def cache-key
  [:spotify-playlist
   {:config {:spotify-host "api.spotify.com"}
    :playlist-id "3b5045a0-05fc-4d7f-8b61-9c6d37ab90e6"}])
```

## Events

Even though `(courier.http/request spec)` looks like a single request, it can
actually spawn multiple requests to several endpoints. Most of the time we're
only interested in the end result, in which case `request` is just what the
doctor ordered.

Sometimes we want more insight into the network layer of our application. Maybe
you want to log each request on the way out and the response coming back.
Courier does all its heavy lifting with `courier.http/make-requests`, but there
is another medium-level abstraction on top of it: `request-with-log`. This
function works just like `request`, except it also gives you a `core.async`
channel that emits events as they occur:

```clj
(require '[courier.http :as http]
         '[clojure.core.async :as a])

(def [ch result]
  (http/request-with-log
   spotify-playlist
   {:cache (courier-cache/from-atom-map cache)
    :params {:client-id "my-api-client"
             :client-secret "api-secret"
             :playlist-id "3abdc"
             :token spotify-token}})

;; The result channel emits the full result, as returned by `request`:
(a/go (::http/data (a/<! result)))

;; The events channel gives you realtime insight into the ongoing process:
(a/go-loop []
  (when-let [event (a/<! ch)]
    (case (::http/event event)
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

      ::http/load-from-cache
      (log/info "Cache hit" (select-keys event [:req :res]))

      ::http/exception
      (log/error event)

      ::http/failure
      (log/error "Failed to complete request" event))))
```

## Changelog

### 2020.11.xx

Initial release

## Acknowledgements

This library is my second attempt at building a more robust tool for HTTP
requests in Clojure. It is a smaller and more focused version of
[Pharmacist](https://github.com/cjohansen/pharmacist), which I now consider a a
flawed execution of a good idea. Courier is based on a bunch of helper functions
I wrote for using Pharmacist primarily for HTTP requests. It attempts to present
the most useful aspects of Pharmacist in a much less ceremonious API that is
closer to traditional low-level HTTP libraries.

## License

Copyright © 2020 Christian Johansen

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
