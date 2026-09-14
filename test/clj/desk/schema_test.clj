(ns desk.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [desk.schema :as schema]))

(deftest config-schema
  (testing "valid config passes"
    (is (schema/valid? schema/Config {:http {:port 8890 :join? false}
                                       :engine {:tick-ms 5000}})))
  (testing "missing keys/wrong types fail, with a humanized explanation"
    (is (not (schema/valid? schema/Config {:http {:port "8890"}})))
    (is (some? (schema/explain-humanized schema/Config {:http {:port "8890"}})))))

(deftest ticker-schema
  (testing "valid ticker entity passes"
    (is (schema/valid? schema/Ticker
                        {:ticker/symbol "AAPL"
                         :ticker/price 190.0
                         :ticker/bid 189.9
                         :ticker/ask 190.1
                         :ticker/day-open 188.0
                         :ticker/high 191.0
                         :ticker/low 187.5
                         :ticker/volume 12345})))
  (testing "missing required keys fail"
    (is (not (schema/valid? schema/Ticker {:ticker/symbol "AAPL"})))))

(deftest order-schema
  (testing "a market buy order passes without a price"
    (is (schema/valid? schema/Order {:ticker "AAPL"
                                      :type :buy
                                      :order-type :market
                                      :shares 10})))
  (testing "a limit sell order passes with a price"
    (is (schema/valid? schema/Order {:ticker "TSLA"
                                      :type :sell
                                      :order-type :limit
                                      :shares 5
                                      :price 250.5})))
  (testing "invalid type/order-type/shares are rejected"
    (is (not (schema/valid? schema/Order {:ticker "AAPL" :type :hold
                                           :order-type :market :shares 1})))
    (is (not (schema/valid? schema/Order {:ticker "AAPL" :type :buy
                                           :order-type :stop :shares 1})))
    (is (not (schema/valid? schema/Order {:ticker "AAPL" :type :buy
                                           :order-type :market :shares -1})))))
