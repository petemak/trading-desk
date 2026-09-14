(ns desk.db
  "DataScript conn + canonical schema (CLAUDE.md's 'Canonical DataScript
  schema' section) plus the seed data view-owning namespaces build
  against. Seeds the same five tickers the backend engine simulates
  (AAPL, TSLA, NVDA, MSFT, AMZN) so the first WS snapshot lines up with
  what's already on screen."
  (:require [datascript.core :as d]))

(def schema
  {:ticker/symbol       {:db/unique :db.unique/identity}
   :portfolio/watchlist {:db/cardinality :db.cardinality/many}
   :tx/id               {:db/unique :db.unique/identity}})

(def tickers
  "The five seed tickers, matching `desk.engine/tickers` on the backend."
  ["AAPL" "TSLA" "NVDA" "MSFT" "AMZN"])

(def seed-prices
  {"AAPL" 190.0
   "TSLA" 250.0
   "NVDA" 120.0
   "MSFT" 420.0
   "AMZN" 180.0})

(defn- seed-ticker [symbol price]
  {:ticker/symbol symbol
   :ticker/price price
   :ticker/bid (- price 0.05)
   :ticker/ask (+ price 0.05)
   :ticker/day-open price
   :ticker/high price
   :ticker/low price
   :ticker/volume 0})

(def initial-data
  (into [{:portfolio/cash 100000}
         {:app/ui true :app/selected-ticker (first tickers)}]
        (map (fn [symbol] (seed-ticker symbol (get seed-prices symbol))))
        tickers))

(defonce conn (d/create-conn schema))

(defn init!
  "Seed `conn` with starting portfolio/tickers/UI state. Idempotent to call
  more than once (re-seeding just re-asserts the same identity-keyed
  entities), but normally only called once at app startup when no
  persisted DB was found in localStorage -- see `desk.storage`."
  []
  (d/transact! conn initial-data))

;; -- Read helpers -----------------------------------------------------
;; Small conveniences for view-owning namespaces; feel free to query
;; `@conn`/`db` directly instead where that's clearer.

(defn all-tickers
  "All ticker entity maps, most convenient for rendering watchlists/tables."
  [db]
  (->> (d/q '[:find [?e ...] :where [?e :ticker/symbol]] db)
       (map #(d/entity db %))))

(defn ticker-by-symbol
  [db symbol]
  (d/entity db [:ticker/symbol symbol]))

(defn cash
  [db]
  (d/q '[:find ?cash . :where [_ :portfolio/cash ?cash]] db))

(defn selected-ticker
  [db]
  (d/q '[:find ?sym . :where [?e :app/ui] [?e :app/selected-ticker ?sym]] db))
