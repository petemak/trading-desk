(ns desk.views.dashboard
  "Dashboard view: four top metrics, an allocation donut, and a
  performance-history chart, all hand-rolled SVG/hiccup for Replicant --
  no charting library.

  Reads `desk.db/conn` (via the `db` value passed into `view`) using
  `desk.db`'s existing read helpers plus a couple of local DataScript
  queries; never touches `desk.db` internals directly.

  ## Two documented gaps this namespace works around

  1. **No stored positions helper.** `desk.db` has no
     `:portfolio/positions` and no `transactions` fn, and nobody has
     built the Trading Engineer yet, so `:tx/*` entities may not exist
     at all. `transactions` below queries for `:tx/id` entities
     directly; an empty result is the normal starting state (100% cash
     allocation, flat performance line), not an error. Held shares per
     ticker are derived by summing `:tx/shares` for `:tx/type :buy`
     minus `:tx/type :sell`, grouped by `:tx/ticker` (see `holdings`).

  2. **No price history is recorded yet.** `desk.ws` only overwrites
     `:ticker/price` on every tick -- it never appends `:history/*`
     facts, even though `:history/{ticker,price,ts}` exists in the
     canonical schema for future use. Since this namespace cannot touch
     `db.cljs`/`ws.cljs` to add real history recording, `view` keeps its
     own in-memory sample buffer (`history-buffer`, a `defonce atom` of
     `{:ts :value}` maps) and appends the current total portfolio value
     to it, throttled to at most one sample per `sample-interval-ms` (4s)
     -- see `append-sample!`. This is the one contained, documented side
     effect in an otherwise pure render path. Real history recording
     belongs in `desk.db`/`desk.ws`, i.e. the State & Backend Architect's
     scope, if the orchestrator wants to fix this properly later."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [desk.db :as db]))

;; -- View-local mutable state (documented, narrowly contained) --------

(defonce ^{:doc "Active performance-chart window toggle. View-local UI
  state -- intentionally not part of DataScript."}
  window-atom
  (atom :1d))

(defonce ^{:doc "In-memory {:ts <ms> :value <total-portfolio-value>}
  sample buffer standing in for the `:history/*` facts nobody is
  recording yet. See namespace docstring, gap #2."}
  history-buffer
  (atom []))

(def sample-interval-ms
  "Minimum spacing between recorded samples, so rapid re-renders don't
  spam `history-buffer`."
  4000)

(def window->ms
  {:1d (* 1 24 60 60 1000)
   :1w (* 7 24 60 60 1000)
   :1m (* 30 24 60 60 1000)
   :1y (* 365 24 60 60 1000)})

;; -- Positions/holdings derivation (gap #1) ----------------------------

(defn transactions
  "All `:tx/*` entities as plain maps, oldest data first is not
  guaranteed -- callers that care about order should sort by `:tx/ts`.
  Empty when no trades have happened yet, which is the normal starting
  state right now (no Trading Engineer has been built)."
  [db]
  (d/q '[:find [(pull ?e [*]) ...] :where [?e :tx/id]] db))

(defn holdings
  "Pure. Given a seq of transaction maps (`:tx/ticker` `:tx/type`
  `:tx/shares`), returns a sorted map of `{ticker-symbol net-shares}`
  for currently-open positions only -- positions netted down to zero or
  negative (fully closed out) are dropped. Unknown `:tx/type` values
  contribute nothing rather than erroring."
  [txs]
  (->> txs
       (reduce (fn [acc {:keys [tx/ticker tx/type tx/shares]}]
                 (let [delta (case type
                               :buy shares
                               :sell (- shares)
                               0)]
                   (update acc ticker (fnil + 0) delta)))
               {})
       (remove (fn [[_ shares]] (<= shares 0)))
       (into (sorted-map))))

(defn ticker-info-map
  "db -> `{symbol {:price ... :day-open ...}}` for every seeded ticker.
  Centralizes the ticker reads so the value-derivation helpers below
  stay pure (plain-map in, plain-map out) and independently testable."
  [db]
  (into {}
        (map (fn [t] [(:ticker/symbol t)
                      {:price (:ticker/price t)
                       :day-open (:ticker/day-open t)}]))
        (db/all-tickers db)))

(defn holdings-value
  "Pure. `{symbol shares}` holdings + `{symbol {:price :day-open}}`
  ticker info -> `[{:symbol :shares :price :value} ...]`, in symbol
  order. Silently skips a symbol with no price info (shouldn't happen
  with the fixed 5-ticker universe, but keeps this defensive rather
  than throwing)."
  [holdings-map ticker-info]
  (keep (fn [[sym shares]]
          (when-let [{:keys [price]} (get ticker-info sym)]
            {:symbol sym :shares shares :price price :value (* shares price)}))
        holdings-map))

(defn portfolio-total
  "Pure. cash + sum of held positions' current market value."
  [cash positions]
  (+ cash (reduce + 0 (map :value positions))))

(defn day-pl
  "Pure. Dollar P/L for currently-held positions only, comparing each
  held symbol's current `:ticker/price` to its `:ticker/day-open`.
  Cash never contributes (it doesn't move independently of trades).
  This is the 'current vs. day-open' choice CLAUDE.md's task brief
  offered as an alternative to buffer-based P/L -- chosen here because
  it's backed by real db data rather than this namespace's synthetic
  sample buffer."
  [holdings-map ticker-info]
  (reduce (fn [acc [sym shares]]
            (if-let [{:keys [price day-open]} (get ticker-info sym)]
              (+ acc (* shares (- price day-open)))
              acc))
          0
          holdings-map))

;; -- Allocation donut math ----------------------------------------------

(def cash-color "#64748b")            ; slate-500
(def ticker-palette
  ["#34d399" "#38bdf8" "#fb7185" "#facc15" "#a78bfa"])

(defn slice-colors
  "Pure. Deterministic colors for `n` allocation slices: cash first,
  then the ticker palette, cycling if there are ever more tickers than
  palette entries."
  [n]
  (take n (cons cash-color (cycle ticker-palette))))

(defn allocation-slices
  "Pure. cash + `[{:symbol :value} ...]` positions -> `[{:label :value
  :pct} ...]`, pct in [0,1] and always summing to 1.0. When the
  portfolio total is zero/negative (or there are no positions, which is
  the default empty-portfolio starting state) this returns a single
  full cash slice instead of dividing by zero / producing NaN -- the
  donut must never render blank or broken."
  [cash positions]
  (let [total (+ cash (reduce + 0 (map :value positions)))]
    (if (or (nil? total) (<= total 0))
      [{:label "Cash" :value (max cash 0) :pct 1.0}]
      (into [{:label "Cash" :value cash :pct (/ cash total)}]
            (map (fn [{:keys [symbol value]}]
                   {:label symbol :value value :pct (/ value total)}))
            positions))))

(defn with-offsets
  "Pure. Adds a running `:offset` (cumulative pct, in [0,1], of all
  preceding slices) to each slice -- the starting point for that
  slice's arc around the donut."
  [slices]
  (first (reduce (fn [[acc offset] slice]
                    [(conj acc (assoc slice :offset offset))
                     (+ offset (:pct slice))])
                  [[] 0]
                  slices)))

;; -- Performance chart math (gap #2 buffer) ------------------------------

(defn append-sample!
  "Records `total-value` into `history-buffer` as `{:ts (js/Date.now)
  :value total-value}`, throttled to at most one sample per
  `sample-interval-ms`. The one side effect in this namespace -- see
  namespace docstring, gap #2. Called once per `view` invocation."
  [total-value]
  (let [now (js/Date.now)
        last (peek @history-buffer)]
    (when (or (nil? last) (>= (- now (:ts last)) sample-interval-ms))
      (swap! history-buffer conj {:ts now :value total-value}))))

(defn filter-window
  "Pure. `samples` (`{:ts :value}` maps, any order) + `now` (ms) +
  `window` keyword -> samples within that window, oldest-first. Falls
  back to the full buffer (still oldest-first) when nothing falls
  inside the requested window -- e.g. the app just opened and only a
  couple of samples exist so far but '1M' is selected -- so the chart
  always has something to draw instead of going blank."
  [samples now window]
  (let [span (get window->ms window (:1d window->ms))
        cutoff (- now span)
        in-window (filter #(>= (:ts %) cutoff) samples)]
    (sort-by :ts (if (seq in-window) in-window samples))))

(defn chart-points
  "Pure. Oldest-first `samples` -> `[[x y] ...]` scaled into a `width`
  x `height` viewBox by the samples' own min/max value. A flat window
  (single sample, or every sample equal -- including the empty-
  portfolio steady state) is centered vertically instead of dividing by
  zero. Empty `samples` -> `[]`."
  [samples width height]
  (if (empty? samples)
    []
    (let [values (map :value samples)
          v-min (apply min values)
          v-max (apply max values)
          v-range (- v-max v-min)
          n (count samples)
          x-step (if (> n 1) (/ width (dec n)) 0)]
      (vec (map-indexed
            (fn [i {:keys [value]}]
              (let [x (* i x-step)
                    y (if (zero? v-range)
                        (/ height 2)
                        (- height (* (/ (- value v-min) v-range) height)))]
                [x y]))
            samples)))))

(defn line-path
  "Pure. `[[x y] ...]` -> an SVG path `d` string tracing the line."
  [points]
  (if (empty? points)
    ""
    (str "M " (str/join " L " (map (fn [[x y]] (str x "," y)) points)))))

(defn area-path
  "Pure. `[[x y] ...]` -> an SVG path `d` string for the soft fill under
  the line, closing down to the chart floor and back to the origin."
  [points width height]
  (if (empty? points)
    ""
    (str (line-path points)
         " L " width "," height
         " L 0," height
         " Z")))

;; -- Formatting -----------------------------------------------------

(defn fmt-usd
  [n]
  (let [n (or n 0)
        sign (if (neg? n) "-" "")]
    (str sign "$" (.toFixed (js/Math.abs n) 2))))

(defn fmt-pct
  [n]
  (str (.toFixed (or n 0) 2) "%"))

;; -- Hiccup: metrics row --------------------------------------------

(defn- metric-card
  [label value color-class]
  [:div.bg-slate-900.border.border-slate-800.rounded-lg.p-4
   [:div.text-xs.uppercase.tracking-wide.text-slate-400 label]
   [:div.text-2xl.font-semibold.mt-1
    {:class (or color-class "text-slate-100")}
    value]])

(defn- metrics-row
  [cash-val total pl pl-pct open-positions]
  [:div.grid.grid-cols-2.md:grid-cols-4.gap-4
   (metric-card "Cash Balance" (fmt-usd cash-val) nil)
   (metric-card "Portfolio Value" (fmt-usd total) nil)
   (metric-card "Day P/L"
                (str (if (neg? pl) "" "+") (fmt-usd pl) " (" (fmt-pct pl-pct) ")")
                (if (neg? pl) "text-rose-400" "text-emerald-400"))
   (metric-card "Open Positions" (str open-positions) nil)])

;; -- Hiccup: allocation donut -----------------------------------------

(defn- donut-svg
  [slices]
  (let [r 40
        circumference (* 2 js/Math.PI r)
        colors (slice-colors (count slices))]
    [:svg {:viewBox "0 0 100 100" :class "w-40 h-40"}
     [:circle {:cx 50 :cy 50 :r r :fill "none"
               :stroke "#1e293b" :stroke-width 16}]
     [:g {:transform "rotate(-90 50 50)"}
      (map (fn [{:keys [pct offset]} color]
             (let [len (* pct circumference)
                   gap (- circumference len)]
               [:circle
                {:replicant/key (str offset "-" color)
                 :cx 50 :cy 50 :r r :fill "none"
                 :stroke color :stroke-width 16
                 :stroke-dasharray (str len " " gap)
                 :stroke-dashoffset (- (* offset circumference))}]))
           slices colors)]]))

(defn- allocation-legend
  [slices]
  (let [colors (slice-colors (count slices))]
    [:ul.w-full.space-y-1
     (map (fn [{:keys [label pct]} color]
            [:li.flex.items-center.justify-between.text-sm.gap-2
             {:replicant/key label}
             [:span.flex.items-center.gap-2.truncate
              [:span.inline-block.w-2.h-2.rounded-full.shrink-0
               {:style {:background-color color}}]
              [:span.truncate label]]
             [:span.text-slate-400 (fmt-pct (* pct 100))]])
          slices colors)]))

(defn- allocation-card
  [slices]
  [:div.bg-slate-900.border.border-slate-800.rounded-lg.p-4.flex.flex-col.items-center.gap-4
   [:h3.text-sm.font-medium.text-slate-300.self-start "Allocation"]
   (donut-svg slices)
   (allocation-legend slices)])

;; -- Hiccup: performance chart ----------------------------------------

(def ^:private windows [:1d :1w :1m :1y])
(def ^:private window-labels {:1d "1D" :1w "1W" :1m "1M" :1y "1Y"})

(defn- window-toggle
  [active]
  [:div.flex.gap-2
   (map (fn [w]
          [:button
           {:replicant/key w
            :class (if (= w active)
                     "px-3 py-1 rounded text-sm border bg-sky-400 text-slate-950 border-sky-400"
                     "px-3 py-1 rounded text-sm border bg-slate-900 text-slate-300 border-slate-800")
            :on {:click (fn [_] (reset! window-atom w))}}
           (window-labels w)])
        windows)])

(defn- performance-card
  [samples window]
  (let [width 300
        height 120
        raw-pts (chart-points samples width height)
        pts (cond
              (empty? raw-pts) []
              (= 1 (count raw-pts)) [(first raw-pts)
                                      [width (second (first raw-pts))]]
              :else raw-pts)
        gaining? (or (< (count samples) 2)
                      (>= (:value (last samples)) (:value (first samples))))
        stroke (if gaining? "#34d399" "#fb7185")]
    [:div.bg-slate-900.border.border-slate-800.rounded-lg.p-4
     [:div.flex.items-center.justify-between.mb-3.gap-4
      [:h3.text-sm.font-medium.text-slate-300 "Performance"]
      (window-toggle window)]
     [:svg {:viewBox (str "0 0 " width " " height) :class "w-full h-32"}
      (when (seq pts)
        [:g
         [:path {:d (area-path pts width height)
                 :fill stroke :fill-opacity "0.15" :stroke "none"}]
         [:path {:d (line-path pts)
                 :fill "none" :stroke stroke :stroke-width "2"}]])]]))

;; -- Top-level view -----------------------------------------------------

(defn view
  "Renders the dashboard: four top metrics, allocation donut, and
  performance chart, given the current `@desk.db/conn` value. Appends
  one sample to `history-buffer` (throttled, see `append-sample!`) as
  its single documented side effect."
  [db]
  (let [cash-val (or (db/cash db) 0)
        holds (holdings (transactions db))
        ticker-info (ticker-info-map db)
        positions (holdings-value holds ticker-info)
        total (portfolio-total cash-val positions)
        pl (day-pl holds ticker-info)
        pl-base (- total pl)
        pl-pct (if (zero? pl-base) 0.0 (* 100 (/ pl pl-base)))
        window @window-atom
        slices (with-offsets (allocation-slices cash-val positions))]
    (append-sample! total)
    (let [samples (filter-window @history-buffer (js/Date.now) window)]
      [:div.p-6.space-y-6.bg-slate-950.text-slate-100.min-h-screen
       (metrics-row cash-val total pl pl-pct (count positions))
       [:div.grid.grid-cols-1.lg:grid-cols-3.gap-6
        [:div.lg:col-span-1 (allocation-card slices)]
        [:div.lg:col-span-2 (performance-card samples window)]]])))
