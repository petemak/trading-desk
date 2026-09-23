(ns desk.views.trading
  "Trading view: ticker search, ticker-detail panel, a hand-rolled SVG
  sparkline, and the order-execution panel, all hiccup for Replicant --
  no charting library.

  Reads `desk.db/conn` (via the `db` value passed into `view`) using
  `desk.db`'s existing read helpers. Holdings are *not* re-derived here
  -- per the orchestrator's decision there is no `:portfolio/positions`/
  `:holding/*` entity and there will not be one, so this namespace
  requires and reuses `desk.views.dashboard/transactions` and
  `desk.views.dashboard/holdings` directly instead of duplicating that
  derivation.

  ## Action dispatch

  Per CLAUDE.md's 'Action dispatch (Replicant)' section, the order
  confirm button emits a data action vector (`[:action/place-order
  order]`) rather than calling `d/transact!` from the render path. This
  namespace extends `desk.core/handle-action!` with a `defmethod` for
  that action -- `desk.core` itself is never edited. `validate-order`
  and `order->tx-data` below are both pure (db + order/plain-data in,
  plain data out); the only side-effecting call in the whole order-
  placement flow is the single `d/transact!` inside the `defmethod`.

  ## Documented gap: no price history yet

  Same gap `desk.views.dashboard` hit and worked around: `desk.ws` only
  overwrites `:ticker/price` on every tick, it never appends
  `:history/*` facts, even though `:history/{ticker,price,ts}` exists in
  the canonical schema for future use. This namespace cannot touch
  `db.cljs`/`ws.cljs` to add real history recording, so the sparkline
  keeps its own small per-ticker in-memory sample buffer
  (`price-history`, a `defonce atom` of `{symbol [{:ts :price} ...]}`),
  appending the ticker's current price each render, throttled to at
  most one sample per `sample-interval-ms`. This is the one contained,
  documented side effect in an otherwise pure render path -- same
  pattern, same justification as `desk.views.dashboard`'s
  `history-buffer`/`append-sample!`. Real history recording belongs in
  `desk.db`/`desk.ws` (State & Backend Architect's scope) if the
  orchestrator wants to fix this properly later."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [desk.core :as core]
            [desk.db :as db]
            [desk.schema :as schema]
            [desk.views.dashboard :as dashboard]))

;; -- View-local mutable state (documented, narrowly contained) ---------

(defonce ^{:doc "Ticker-search input text. View-local UI state --
  intentionally not part of DataScript."}
  search-atom
  (atom ""))

(defonce ^{:doc "The in-progress order the execution panel is building.
  View-local UI state -- intentionally not part of DataScript; only a
  *validated* order ever gets transacted, via `order->tx-data` inside
  the `:action/place-order` handler below."}
  order-draft
  (atom {:type :buy :order-type :market :shares 1 :price nil}))

(defonce ^{:doc "In-memory {symbol [{:ts <ms> :price <num>} ...]} sample
  buffer standing in for the `:history/*` facts nobody is recording yet.
  See namespace docstring."}
  price-history
  (atom {}))

(def sample-interval-ms
  "Minimum spacing between recorded samples per ticker, so rapid
  re-renders don't spam `price-history`."
  4000)

(defn append-price-sample!
  "Records `price` for `symbol` into `price-history`, throttled to at
  most one sample per `sample-interval-ms`. The one side effect in this
  namespace -- see namespace docstring."
  [symbol price]
  (let [now (js/Date.now)
        buf (get @price-history symbol [])
        last (peek buf)]
    (when (or (nil? last) (>= (- now (:ts last)) sample-interval-ms))
      (swap! price-history update symbol (fnil conj []) {:ts now :price price}))))

;; -- Pure business logic -------------------------------------------------

(defn validate-order
  "db: a DataScript db value. order: {:ticker <symbol> :type (:buy|:sell)
  :order-type (:market|:limit) :shares <int> :price <number, only for
  :limit>}. Returns nil when the order is valid, or a non-nil vector of
  readable error strings otherwise.

  First validates shape with Malli against `desk.schema/Order`; a
  malformed order short-circuits business-rule checking entirely and
  returns the humanized Malli error map as-is. A shape-valid order then
  has every applicable business rule checked (not short-circuited), so
  multiple simultaneous problems can all be reported at once."
  [db order]
  (or (schema/explain-humanized schema/Order order)
      (let [{:keys [ticker type order-type shares price]} order
            ticker-entity (db/ticker-by-symbol db ticker)
            limit-missing-price? (and (= order-type :limit) (nil? price))
            exec-price (cond
                         (= order-type :market) (:ticker/price ticker-entity)
                         (= order-type :limit) price)
            held (get (dashboard/holdings (dashboard/transactions db)) ticker 0)
            errors
            (cond-> []
              (nil? ticker-entity)
              (conj (str "Unknown ticker: " ticker))

              limit-missing-price?
              (conj "Limit orders require a price")

              (and (= type :buy)
                   (some? ticker-entity)
                   (not limit-missing-price?)
                   (> (* shares exec-price) (db/cash db)))
              (conj "Insufficient cash for this order")

              (and (= type :sell)
                   (> shares held))
              (conj (str "Insufficient shares: you own " held ", tried to sell " shares)))]
        (when (seq errors) errors))))

(defn order->tx-data
  "Pure. `db` + an `order` that has already passed `validate-order` -> a
  DataScript tx-data vector: a new `:tx/*` entity plus a
  `:portfolio/cash` update on the portfolio singleton (subtract the
  order total for a buy, add it back for a sell)."
  [db order]
  (let [{:keys [ticker type order-type shares price]} order
        exec-price (if (= order-type :market)
                     (:ticker/price (db/ticker-by-symbol db ticker))
                     price)
        total (* shares exec-price)
        cash-eid (d/q '[:find ?e . :where [?e :portfolio/cash]] db)
        cash (db/cash db)
        new-cash (case type
                   :buy (- cash total)
                   :sell (+ cash total))]
    [{:tx/id (str (random-uuid))
      :tx/ticker ticker
      :tx/type type
      :tx/order-type order-type
      :tx/shares shares
      :tx/price exec-price
      :tx/total total
      :tx/ts (js/Date.now)}
     {:db/id cash-eid :portfolio/cash new-cash}]))

(defn matching-tickers
  "Pure. `tickers` (seq of ticker entities/maps with `:ticker/symbol`) +
  `query` (search text) -> the subset whose symbol contains `query`
  (case-insensitive substring match). Blank query matches everything."
  [tickers query]
  (let [q (str/lower-case (or query ""))]
    (if (str/blank? q)
      tickers
      (filter #(str/includes? (str/lower-case (:ticker/symbol %)) q) tickers))))

;; -- Action dispatch -------------------------------------------------

(defmethod core/handle-action! :action/place-order
  [_event [_ order]]
  (let [db @db/conn]
    (when (nil? (validate-order db order))
      (d/transact! db/conn (order->tx-data db order)))))

(defmethod core/handle-action! :action/select-ticker
  [_event [_ symbol]]
  (let [db @db/conn
        ui-eid (d/q '[:find ?e . :where [?e :app/selected-ticker]] db)]
    (d/transact! db/conn [{:db/id ui-eid :app/selected-ticker symbol}])))

;; -- Formatting -----------------------------------------------------

(defn fmt-usd
  [n]
  (let [n (or n 0)
        sign (if (neg? n) "-" "")]
    (str sign "$" (.toFixed (js/Math.abs n) 2))))

;; -- Hiccup: search --------------------------------------------------

(defn- search-panel
  [tickers query]
  (let [results (matching-tickers tickers query)]
    [:div.bg-slate-900.border.border-slate-800.rounded-lg.p-4.space-y-3
     [:h3.text-sm.font-medium.text-slate-300 "Search"]
     [:input.w-full.bg-slate-950.border.border-slate-800.rounded.px-3.py-2.text-sm.text-slate-100
      {:type "text"
       :placeholder "Symbol, e.g. AAPL"
       :value query
       :on {:input (fn [e]
                     (reset! search-atom
                             (.. (:replicant/dom-event e) -target -value)))}}]
     [:ul.divide-y.divide-slate-800
      (map (fn [t]
             (let [sym (:ticker/symbol t)]
               [:li {:replicant/key sym}
                [:button.w-full.flex.items-center.justify-between.py-2.text-left.hover:bg-slate-800.px-2.rounded
                 {:on {:click [[:action/select-ticker sym]]}}
                 [:span.font-medium.text-slate-100 sym]
                 [:span.text-slate-400 (fmt-usd (:ticker/price t))]]]))
           results)]]))

;; -- Hiccup: sparkline -------------------------------------------------

(defn sparkline-points
  "Pure. Oldest-first `samples` (`{:ts :price}` maps) -> `[[x y] ...]`
  scaled into a `width` x `height` viewBox by the samples' own min/max
  price. A flat window (0/1 sample, or every sample equal) is centered
  vertically instead of dividing by zero."
  [samples width height]
  (if (empty? samples)
    []
    (let [prices (map :price samples)
          v-min (apply min prices)
          v-max (apply max prices)
          v-range (- v-max v-min)
          n (count samples)
          x-step (if (> n 1) (/ width (dec n)) 0)]
      (vec (map-indexed
            (fn [i {:keys [price]}]
              (let [x (* i x-step)
                    y (if (zero? v-range)
                        (/ height 2)
                        (- height (* (/ (- price v-min) v-range) height)))]
                [x y]))
            samples)))))

(defn sparkline-path
  "Pure. `[[x y] ...]` -> an SVG path `d` string tracing the line."
  [points]
  (if (empty? points)
    ""
    (str "M " (str/join " L " (map (fn [[x y]] (str x "," y)) points)))))

(defn- sparkline-svg
  [samples gaining?]
  (let [width 280
        height 80
        raw-pts (sparkline-points samples width height)
        pts (cond
              (empty? raw-pts) []
              (= 1 (count raw-pts)) [(first raw-pts) [width (second (first raw-pts))]]
              :else raw-pts)
        stroke (if gaining? "#34d399" "#fb7185")]
    [:svg {:viewBox (str "0 0 " width " " height) :class "w-full h-20"}
     (when (seq pts)
       [:path {:d (sparkline-path pts)
               :fill "none" :stroke stroke :stroke-width "2"}])]))

;; -- Hiccup: ticker detail ---------------------------------------------

(defn- detail-row
  [label value]
  [:div.flex.items-center.justify-between.text-sm
   [:span.text-slate-400 label]
   [:span.text-slate-100 value]])

(defn- ticker-detail-panel
  [ticker held-shares samples]
  (if (nil? ticker)
    [:div.bg-slate-900.border.border-slate-800.rounded-lg.p-4.text-slate-400
     "No ticker selected"]
    (let [{:ticker/keys [symbol price bid ask day-open high low volume]} ticker
          gaining? (>= price day-open)]
      [:div.bg-slate-900.border.border-slate-800.rounded-lg.p-4.space-y-4
       [:div.flex.items-baseline.justify-between
        [:h2.text-xl.font-semibold.text-slate-100 symbol]
        [:span.text-lg
         {:class (if gaining? "text-emerald-400" "text-rose-400")}
         (fmt-usd price)]]
       (sparkline-svg samples gaining?)
       [:div.space-y-1
        (detail-row "Bid / Ask" (str (fmt-usd bid) " / " (fmt-usd ask)))
        (detail-row "Day Range" (str (fmt-usd low) " - " (fmt-usd high)))
        (detail-row "Day Open" (fmt-usd day-open))
        (detail-row "Volume" (str volume))
        (detail-row "Shares Held" (str held-shares))]])))

;; -- Hiccup: order execution panel ---------------------------------------

(defn- side-button
  [current side label color-class]
  [:button
   {:class (str "px-3 py-1 rounded text-sm border "
                (if (= current side)
                  (str color-class " text-slate-950 border-transparent")
                  "bg-slate-950 text-slate-300 border-slate-800"))
    :on {:click (fn [_] (swap! order-draft assoc :type side))}}
   label])

(defn- order-type-button
  [current order-type label]
  [:button
   {:class (str "px-3 py-1 rounded text-sm border "
                (if (= current order-type)
                  "bg-sky-400 text-slate-950 border-transparent"
                  "bg-slate-950 text-slate-300 border-slate-800"))
    :on {:click (fn [_] (swap! order-draft assoc :order-type order-type))}}
   label])

(defn- errors->str
  [errors]
  (cond
    (string? errors) errors
    (sequential? errors) (str/join "; " errors)
    (map? errors) (str/join "; " (mapcat (fn [[k v]] (map #(str (name k) ": " %) v)) errors))
    :else (str errors)))

(defn- order-panel
  [db ticker draft]
  (let [order (assoc draft :ticker (:ticker/symbol ticker))
        errors (when ticker (validate-order db order))]
    [:div.bg-slate-900.border.border-slate-800.rounded-lg.p-4.space-y-4
     [:h3.text-sm.font-medium.text-slate-300 "Place Order"]
     [:div.flex.gap-2
      (side-button (:type draft) :buy "Buy" "bg-emerald-400")
      (side-button (:type draft) :sell "Sell" "bg-rose-400")]
     [:div.flex.gap-2
      (order-type-button (:order-type draft) :market "Market")
      (order-type-button (:order-type draft) :limit "Limit")]
     [:label.block.text-sm.text-slate-400
      "Shares"
      [:input.w-full.mt-1.bg-slate-950.border.border-slate-800.rounded.px-3.py-2.text-sm.text-slate-100
       {:type "number"
        :min "1"
        :value (:shares draft)
        :on {:input (fn [e]
                      (let [v (js/parseInt (.. (:replicant/dom-event e) -target -value) 10)]
                        (swap! order-draft assoc :shares (if (js/isNaN v) nil v))))}}]]
     (when (= :limit (:order-type draft))
       [:label.block.text-sm.text-slate-400
        "Limit price"
        [:input.w-full.mt-1.bg-slate-950.border.border-slate-800.rounded.px-3.py-2.text-sm.text-slate-100
         {:type "number"
          :min "0"
          :step "0.01"
          :value (:price draft)
          :on {:input (fn [e]
                        (let [v (js/parseFloat (.. (:replicant/dom-event e) -target -value))]
                          (swap! order-draft assoc :price (if (js/isNaN v) nil v))))}}]])
     (when errors
       [:div.text-sm.text-rose-400 (errors->str errors)])
     [:button.w-full.py-2.rounded.font-medium
      {:class (if (some? errors)
                "bg-slate-800 text-slate-500 cursor-not-allowed"
                "bg-sky-400 text-slate-950")
       :disabled (some? errors)
       :on {:click (when (nil? errors) [[:action/place-order order]])}}
      "Place order"]]))

;; -- Top-level view -----------------------------------------------------

(defn view
  "Renders the trading view: search, ticker detail + sparkline, and the
  order execution panel, given the current `@desk.db/conn` value.
  Appends one price sample for the selected ticker to `price-history`
  (throttled, see `append-price-sample!`) as its single documented side
  effect."
  [db]
  (let [tickers (db/all-tickers db)
        selected-symbol (db/selected-ticker db)
        ticker (when selected-symbol (db/ticker-by-symbol db selected-symbol))
        holds (dashboard/holdings (dashboard/transactions db))
        held-shares (get holds selected-symbol 0)
        query @search-atom
        draft @order-draft]
    (when ticker
      (append-price-sample! selected-symbol (:ticker/price ticker)))
    (let [samples (get @price-history selected-symbol [])]
      [:div.p-6.bg-slate-950.text-slate-100.min-h-screen
       [:div.grid.grid-cols-1.lg:grid-cols-3.gap-6
        [:div.lg:col-span-1 (search-panel tickers query)]
        [:div.lg:col-span-1 (ticker-detail-panel ticker held-shares samples)]
        [:div.lg:col-span-1 (order-panel db ticker draft)]]])))
