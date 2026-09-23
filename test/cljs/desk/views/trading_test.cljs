(ns desk.views.trading-test
  "Tests for the pure helpers in `desk.views.trading` -- `validate-order`
  and `order->tx-data`, plus the search/sparkline math. Per the task
  brief there is currently no shadow-cljs config and no cljs test runner
  wired into `clojure -M:test` (only `test/clj/desk/*` runs via kaocha),
  so this namespace cannot be executed in this environment -- written
  correctly anyway, for when cljs test infra lands.

  `validate-order`/`order->tx-data` take a DataScript db value, so tests
  build a small in-memory conn with `desk.db/schema` rather than mocking
  db reads."
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [desk.db :as db]
            [desk.views.trading :as sut]))

(defn- test-db
  "A fresh DataScript db value seeded with one ticker (AAPL @ 100),
  `cash` in the portfolio singleton, and the given `:tx/*` transactions
  (already-placed orders backing the holdings a sell-validation test
  needs)."
  [{:keys [cash txs] :or {cash 10000 txs []}}]
  (let [conn (d/create-conn db/schema)]
    (d/transact! conn
                 (into [{:portfolio/cash cash}
                        {:app/ui true :app/selected-ticker "AAPL"}
                        {:ticker/symbol "AAPL"
                         :ticker/price 100.0
                         :ticker/bid 99.95
                         :ticker/ask 100.05
                         :ticker/day-open 95.0
                         :ticker/high 101.0
                         :ticker/low 94.0
                         :ticker/volume 1000}]
                       txs))
    @conn))

;; -- validate-order ----------------------------------------------------

(deftest valid-market-buy-test
  (testing "a well-formed, affordable market buy is valid"
    (let [db (test-db {:cash 10000})
          order {:ticker "AAPL" :type :buy :order-type :market :shares 10}]
      (is (nil? (sut/validate-order db order))))))

(deftest valid-limit-sell-test
  (testing "a well-formed limit sell within held shares is valid"
    (let [db (test-db {:txs [{:tx/id "1" :tx/ticker "AAPL" :tx/type :buy
                               :tx/shares 20 :tx/order-type :market
                               :tx/price 90.0 :tx/total 1800.0 :tx/ts 1}]})
          order {:ticker "AAPL" :type :sell :order-type :limit :shares 5 :price 105.0}]
      (is (nil? (sut/validate-order db order))))))

(deftest insufficient-cash-test
  (testing "a buy costing more than available cash is rejected"
    (let [db (test-db {:cash 500})
          order {:ticker "AAPL" :type :buy :order-type :market :shares 10}]
      (is (some? (sut/validate-order db order))))))

(deftest insufficient-shares-test
  (testing "selling more shares than held is rejected"
    (let [db (test-db {})
          order {:ticker "AAPL" :type :sell :order-type :market :shares 5}]
      (is (some? (sut/validate-order db order))))))

(deftest malformed-order-shape-test
  (testing "a malli-invalid order (bad :type) short-circuits with the humanized error"
    (let [db (test-db {})
          order {:ticker "AAPL" :type :hold :order-type :market :shares 10}]
      (is (some? (sut/validate-order db order))))))

(deftest limit-order-missing-price-test
  (testing "limit orders require :price even though it's a Malli-optional key"
    (let [db (test-db {})
          order {:ticker "AAPL" :type :buy :order-type :limit :shares 1}]
      (is (some? (sut/validate-order db order))))))

(deftest unknown-ticker-test
  (testing "a ticker not present in db is rejected"
    (let [db (test-db {})
          order {:ticker "GME" :type :buy :order-type :market :shares 1}]
      (is (some? (sut/validate-order db order))))))

(deftest multiple-simultaneous-errors-test
  (testing "unaffordable buy for an unknown ticker reports more than one problem"
    (let [db (test-db {:cash 1})
          order {:ticker "GME" :type :buy :order-type :market :shares 1000}
          errors (sut/validate-order db order)]
      (is (some? errors))
      (is (>= (count errors) 1))
      (is (some #(re-find #"Unknown ticker" %) errors)))))

;; -- order->tx-data ----------------------------------------------------

(deftest order->tx-data-market-buy-test
  (testing "market buy tx-data uses the current ticker price and debits cash"
    (let [db (test-db {:cash 10000})
          order {:ticker "AAPL" :type :buy :order-type :market :shares 10}
          [tx cash-update] (sut/order->tx-data db order)]
      (is (= "AAPL" (:tx/ticker tx)))
      (is (= :buy (:tx/type tx)))
      (is (= 10 (:tx/shares tx)))
      (is (= 100.0 (:tx/price tx)))
      (is (= 1000.0 (:tx/total tx)))
      (is (= 9000.0 (:portfolio/cash cash-update))))))

(deftest order->tx-data-limit-sell-test
  (testing "limit sell tx-data uses the given limit price and credits cash"
    (let [db (test-db {:cash 10000})
          order {:ticker "AAPL" :type :sell :order-type :limit :shares 5 :price 110.0}
          [tx cash-update] (sut/order->tx-data db order)]
      (is (= 110.0 (:tx/price tx)))
      (is (= 550.0 (:tx/total tx)))
      (is (= 10550.0 (:portfolio/cash cash-update))))))

;; -- matching-tickers ----------------------------------------------------

(deftest matching-tickers-blank-query-test
  (testing "blank query matches every ticker"
    (let [tickers [{:ticker/symbol "AAPL"} {:ticker/symbol "TSLA"}]]
      (is (= tickers (sut/matching-tickers tickers ""))))))

(deftest matching-tickers-substring-test
  (testing "case-insensitive substring match"
    (let [tickers [{:ticker/symbol "AAPL"} {:ticker/symbol "TSLA"}]]
      (is (= [{:ticker/symbol "AAPL"}] (sut/matching-tickers tickers "aa"))))))

;; -- sparkline-points ----------------------------------------------------

(deftest sparkline-points-empty-test
  (is (= [] (sut/sparkline-points [] 100 50))))

(deftest sparkline-points-flat-centered-test
  (testing "all-equal samples are centered vertically instead of NaN"
    (let [pts (sut/sparkline-points [{:ts 1 :price 10} {:ts 2 :price 10}] 100 50)]
      (is (every? (fn [[_ y]] (= 25 y)) pts)))))
