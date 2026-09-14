(ns desk.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [desk.engine :as engine]))

(deftest next-price-never-non-positive
  (testing "across many samples and starting prices, next-price stays > 0"
    (doseq [start-price [0.01 1.0 5.0 100.0 250.0 1000.0]]
      (dotimes [_ 5000]
        (is (pos? (engine/next-price start-price)))))))

(deftest next-price-deterministic-with-injected-rand-fn
  (testing "an injectable rand-fn makes the walk deterministic"
    (is (= (engine/next-price 100.0 (constantly 0.0))
           (engine/next-price 100.0 (constantly 0.0))))
    ;; rand-fn returning 0.0 -> pct = -0.01 -> price * 0.99
    (is (= 99.0 (engine/next-price 100.0 (constantly 0.0))))
    ;; rand-fn returning 1.0 (max of [0,1) in practice, but exercise the
    ;; boundary anyway) -> pct = +0.01 -> price * 1.01
    (is (= 101.0 (engine/next-price 100.0 (constantly 1.0))))))

(deftest next-price-floors-at-a-positive-minimum
  (testing "even a tiny starting price never walks to zero or below"
    (dotimes [_ 1000]
      (is (pos? (engine/next-price 0.02))))))

(deftest tick-prices-never-non-positive
  (testing "a full tick over all seed tickers keeps every price positive"
    (dotimes [_ 500]
      (let [next-prices (engine/tick-prices engine/seed-prices)]
        (is (= (set (keys engine/seed-prices)) (set (keys next-prices))))
        (doseq [[_sym price] next-prices]
          (is (pos? price)))))))

(deftest engine-component-lifecycle
  (testing "start creates the client-registry atom and price loop; stop tears them down"
    (let [started (component/start (engine/new-engine))]
      (is (some? (:clients started)))
      (is (some? (:prices started)))
      (is (some? (:running started)))
      (is (some? (:thread started)))
      (is (true? @(:running started)))
      (is (= engine/seed-prices @(:prices started)))
      (let [stopped (component/stop started)]
        (is (nil? (:clients stopped)))
        (is (nil? (:prices stopped)))
        (is (nil? (:running stopped)))
        (is (nil? (:thread stopped)))))))

(deftest register-and-unregister-client
  (testing "register-client!/unregister-client! manage the client set"
    (let [started (component/start (engine/new-engine))
          ch (Object.)]
      (try
        (engine/register-client! started ch)
        (is (contains? @(:clients started) ch))
        (engine/unregister-client! started ch)
        (is (not (contains? @(:clients started) ch)))
        (finally
          (component/stop started))))))
