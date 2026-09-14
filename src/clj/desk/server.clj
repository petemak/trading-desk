(ns desk.server
  "Jetty HTTP/WS server Component. Depends on `:config` and `:engine`;
  builds the pedestal service map via `desk.service` and manages the Jetty
  lifecycle (start/stop) only -- routing and WS wiring live in
  `desk.service`."
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]
            [desk.service :as service]))

(defrecord Pedestal [config engine runtime]
  component/Lifecycle
  (start [this]
    (if runtime
      this
      (let [runtime* (-> (service/service-map config engine)
                          http/create-server
                          http/start)]
        (assoc this :runtime runtime*))))

  (stop [this]
    (when runtime (http/stop runtime))
    (assoc this :runtime nil)))

(defn new-server []
  (map->Pedestal {}))
