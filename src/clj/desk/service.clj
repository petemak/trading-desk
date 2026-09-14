(ns desk.service
  "Pedestal routes and WebSocket wiring. Builds a pedestal service map given
  a validated config and a started `desk.engine` component; Jetty lifecycle
  itself lives in `desk.server`."
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.jetty.websockets :as ws]
            [clojure.core.async :as async]
            [desk.engine :as engine]))

(defn health-handler
  [_request]
  {:status 200 :body "ok"})

(def routes
  (route/expand-routes
   #{["/health" :get health-handler :route-name :health]}))

(defn- listener-fn
  "Returns a fn suitable for `add-ws-endpoints`'s `:listener-fn` option.
  Built fresh per-connection (Jetty invokes this on every upgrade request),
  so each connection gets its own closed-over `send-ch` reference -- this
  is what lets `:on-close`/`:on-error` clean up the right channel from the
  engine's client registry, which the shared `ws-map` value can't do on
  its own since Pedestal's `onWebSocketClose` doesn't receive the session."
  [engine]
  (fn [_req _response _ws-map]
    (let [send-ch (atom nil)
          cleanup! (fn []
                     (when-let [ch @send-ch]
                       (engine/unregister-client! engine ch)))]
      (ws/make-ws-listener
       {:on-connect (ws/start-ws-connection
                     (fn [_ws-session ch]
                       (reset! send-ch ch)
                       (engine/register-client! engine ch)
                       (async/put! ch (pr-str {:type :snapshot
                                                :prices (engine/snapshot engine)}))))
        :on-close (fn [_status-code _reason] (cleanup!))
        :on-error (fn [_cause] (cleanup!))}))))

(defn ws-paths
  "Path -> ws-map. The ws-map value here is unused (see `listener-fn`
  above) but `add-ws-endpoints` needs a non-empty map to register the
  servlet for the path."
  [_engine]
  {"/ws" {}})

(defn service-map
  [config engine]
  (-> {::http/routes routes
       ::http/type :jetty
       ::http/port (get-in config [:http :port])
       ::http/join? (get-in config [:http :join?])
       ::http/container-options
       {:context-configurator
        (fn [ctx]
          (ws/add-ws-endpoints ctx (ws-paths engine)
                                {:listener-fn (listener-fn engine)}))}}
      http/default-interceptors))
