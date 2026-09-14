(ns desk.ws
  "WebSocket client connecting to the backend's `/ws` endpoint (see
  `desk.service`/`desk.engine`). Transacts incoming ticks straight into
  the DataScript conn from `desk.db`.

  Wire message shape (edn, `pr-str`'d by the backend):
    {:type :snapshot :prices {:AAPL 190.23 ...}}   ; sent once, on connect
    {:type :tick     :prices {:AAPL 190.31 ...} :ts 1699999999999} ; every tick"
  (:require [cljs.reader :as reader]
            [datascript.core :as d]
            [desk.db :as db]))

(defonce socket (atom nil))

(defn ws-url
  []
  (let [proto (if (= "https:" (.-protocol js/location)) "wss:" "ws:")]
    (str proto "//" (.-host js/location) "/ws")))

(defn- prices->tx-data
  [prices]
  (mapv (fn [[symbol price]]
          {:ticker/symbol (name symbol)
           :ticker/price price})
        prices))

(defn- apply-message! [{:keys [type prices]}]
  (when (and (contains? #{:tick :snapshot} type) (seq prices))
    (d/transact! db/conn (prices->tx-data prices))))

(defn- handle-message [event]
  (let [msg (reader/read-string (.-data event))]
    (apply-message! msg)))

(defn connect!
  "Open the WS connection and start transacting incoming ticks. Safe to
  call once at app startup."
  []
  (when-not @socket
    (let [sock (js/WebSocket. (ws-url))]
      (set! (.-onmessage sock) handle-message)
      (set! (.-onclose sock) (fn [_] (reset! socket nil)))
      (reset! socket sock))))

(defn disconnect!
  []
  (when-let [sock @socket]
    (.close sock)
    (reset! socket nil)))
