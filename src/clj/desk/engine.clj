(ns desk.engine
  "Price-simulation Component.

  Owns two pieces of mutable runtime state, both created in `start` and
  torn down in `stop` -- there is no top-level `future`/`def`'d atom:

    - `clients` -- a set of core.async channels, one per connected WS
      client (see `desk.service`), used to broadcast ticks.
    - `prices`  -- the current simulated price for each seed ticker.

  A background thread wakes up every `(:tick-ms config)` milliseconds,
  advances every ticker's price with a bounded random walk, and broadcasts
  the resulting snapshot to every registered client."
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]))

(def tickers
  "The five seed tickers this desk simulates prices for."
  [:AAPL :TSLA :NVDA :MSFT :AMZN])

(def seed-prices
  {:AAPL 190.0
   :TSLA 250.0
   :NVDA 120.0
   :MSFT 420.0
   :AMZN 180.0})

(def default-tick-ms 5000)

(defn next-price
  "Bounded random walk: `price` moves by up to +/-1%, never returning a
  non-positive value. `rand-fn` is injectable (must return a value in
  [0, 1), like `clojure.core/rand`) so the walk is deterministic in tests."
  ([price] (next-price price rand))
  ([price rand-fn]
   (let [pct (- (* (rand-fn) 0.02) 0.01)
         walked (* price (+ 1.0 pct))]
     (max 0.01 walked))))

(defn tick-prices
  "Given a map of symbol->price, return the next map of symbol->price."
  ([prices] (tick-prices prices rand))
  ([prices rand-fn]
   (into {} (map (fn [[sym price]] [sym (next-price price rand-fn)])) prices)))

(defn snapshot
  "The engine's current symbol->price map."
  [engine]
  @(:prices engine))

(defn register-client!
  "Register a core.async channel to receive future tick broadcasts."
  [engine ch]
  (swap! (:clients engine) conj ch))

(defn unregister-client!
  "Stop broadcasting ticks to `ch`."
  [engine ch]
  (swap! (:clients engine) disj ch))

(defn broadcast!
  "Put `payload` onto every registered client channel."
  [clients payload]
  (doseq [ch @clients]
    (async/put! ch payload)))

(defn- run-loop! [{:keys [prices clients running tick-ms]}]
  (while @running
    (Thread/sleep ^long tick-ms)
    (when @running
      (let [next (tick-prices @prices)]
        (reset! prices next)
        (broadcast! clients (pr-str {:type :tick
                                      :prices next
                                      :ts (System/currentTimeMillis)}))))))

(defrecord Engine [config prices clients running thread]
  component/Lifecycle
  (start [this]
    (if clients
      this
      (let [clients* (atom #{})
            prices*  (atom seed-prices)
            running* (atom true)
            tick-ms  (get-in config [:engine :tick-ms] default-tick-ms)
            thread*  (doto (Thread. ^Runnable
                                    (fn []
                                      (run-loop! {:prices prices*
                                                  :clients clients*
                                                  :running running*
                                                  :tick-ms tick-ms}))
                                    "desk-engine-price-loop")
                       (.setDaemon true)
                       .start)]
        (assoc this :prices prices* :clients clients* :running running* :thread thread*))))

  (stop [this]
    (when running (reset! running false))
    (when thread (.interrupt ^Thread thread))
    (assoc this :prices nil :clients nil :running nil :thread nil)))

(defn new-engine []
  (map->Engine {}))
