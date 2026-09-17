(ns desk.views.dashboard-test
  "Tests for the pure helpers in `desk.views.dashboard` -- holdings
  derivation, allocation-slice/wedge-angle math, and sample-buffer
  window filtering/scaling. Per the task brief there is currently no
  shadow-cljs config and no cljs test runner wired into `clojure -M:test`
  (only `test/clj/desk/*` runs via kaocha), so this namespace cannot be
  executed in this environment -- written correctly anyway, for when
  cljs test infra lands."
  (:require [cljs.test :refer [deftest is testing]]
            [desk.views.dashboard :as sut]))

;; -- holdings ------------------------------------------------------

(deftest holdings-empty-test
  (testing "no transactions -> no holdings (the current default state)"
    (is (= {} (sut/holdings [])))))

(deftest holdings-buy-sell-test
  (testing "net shares = buys - sells, grouped by ticker"
    (is (= {"AAPL" 5}
           (sut/holdings [{:tx/ticker "AAPL" :tx/type :buy :tx/shares 10}
                           {:tx/ticker "AAPL" :tx/type :sell :tx/shares 5}])))))

(deftest holdings-drops-closed-positions-test
  (testing "fully closed (<=0) positions are dropped, not shown at 0/neg shares"
    (is (= {} (sut/holdings [{:tx/ticker "TSLA" :tx/type :buy :tx/shares 3}
                              {:tx/ticker "TSLA" :tx/type :sell :tx/shares 3}])))
    (is (= {} (sut/holdings [{:tx/ticker "TSLA" :tx/type :buy :tx/shares 3}
                              {:tx/ticker "TSLA" :tx/type :sell :tx/shares 5}])))))

(deftest holdings-multi-ticker-sorted-test
  (testing "returns a sorted map so downstream rendering order is deterministic"
    (let [h (sut/holdings [{:tx/ticker "TSLA" :tx/type :buy :tx/shares 1}
                            {:tx/ticker "AAPL" :tx/type :buy :tx/shares 1}])]
      (is (= ["AAPL" "TSLA"] (keys h))))))

;; -- holdings-value / portfolio-total / day-pl ----------------------

(def ticker-info
  {"AAPL" {:price 200.0 :day-open 190.0}
   "TSLA" {:price 240.0 :day-open 250.0}})

(deftest holdings-value-test
  (is (= [{:symbol "AAPL" :shares 2 :price 200.0 :value 400.0}
          {:symbol "TSLA" :shares 1 :price 240.0 :value 240.0}]
         (sut/holdings-value {"AAPL" 2 "TSLA" 1} ticker-info))))

(deftest holdings-value-skips-unknown-symbol-test
  (is (= [] (sut/holdings-value {"GME" 10} ticker-info))))

(deftest portfolio-total-test
  (is (= 100640.0
         (sut/portfolio-total 100000.0
                               (sut/holdings-value {"AAPL" 2 "TSLA" 1} ticker-info)))))

(deftest day-pl-test
  (testing "gain on AAPL (+10/share * 2), loss on TSLA (-10/share * 1) nets to +10"
    (is (= 10.0 (sut/day-pl {"AAPL" 2 "TSLA" 1} ticker-info)))))

(deftest day-pl-empty-holdings-test
  (is (= 0 (sut/day-pl {} ticker-info))))

;; -- allocation-slices / with-offsets (donut wedge math) -------------

(deftest allocation-slices-empty-portfolio-test
  (testing "no holdings -> a single full cash slice, never NaN/divide-by-zero"
    (is (= [{:label "Cash" :value 100000 :pct 1.0}]
           (sut/allocation-slices 100000 [])))))

(deftest allocation-slices-zero-total-test
  (testing "zero cash and zero positions still yields a single (empty) cash slice"
    (is (= [{:label "Cash" :value 0 :pct 1.0}]
           (sut/allocation-slices 0 [])))))

(deftest allocation-slices-with-positions-test
  (let [slices (sut/allocation-slices 500 [{:symbol "AAPL" :value 500}])]
    (is (= [{:label "Cash" :value 500 :pct 0.5}
            {:label "AAPL" :value 500 :pct 0.5}]
           slices))
    (testing "percentages always sum to 1.0"
      (is (= 1.0 (reduce + (map :pct slices)))))))

(deftest with-offsets-test
  (is (= [{:pct 0.5 :offset 0}
          {:pct 0.25 :offset 0.5}
          {:pct 0.25 :offset 0.75}]
         (sut/with-offsets [{:pct 0.5} {:pct 0.25} {:pct 0.25}]))))

;; -- filter-window ---------------------------------------------------

(def hour 3600000)

(deftest filter-window-basic-test
  (let [now 1000000000
        samples [{:ts (- now (* 2 hour)) :value 1}
                  {:ts (- now hour) :value 2}
                  {:ts now :value 3}]]
    (testing "1D window with only recent samples keeps everything, oldest-first"
      (is (= [1 2 3] (map :value (sut/filter-window samples now :1d)))))))

(deftest filter-window-excludes-old-samples-test
  (let [now (* 400 24 hour)
        samples [{:ts 0 :value :ancient}
                  {:ts now :value :fresh}]]
    (is (= [:fresh] (map :value (sut/filter-window samples now :1d))))))

(deftest filter-window-falls-back-when-empty-test
  (testing "when nothing falls inside the window (e.g. app just opened, '1M'
            selected), fall back to the full buffer instead of going blank"
    (let [now (* 400 24 hour)
          samples [{:ts 0 :value :only-sample}]]
      (is (= [:only-sample] (map :value (sut/filter-window samples now :1d)))))))

;; -- chart-points ------------------------------------------------------

(deftest chart-points-empty-test
  (is (= [] (sut/chart-points [] 300 120))))

(deftest chart-points-flat-line-centered-test
  (testing "equal values (incl. the single-sample / all-cash steady state)
            center vertically instead of dividing by zero"
    (is (= [[0 60] [300 60]]
           (sut/chart-points [{:value 100} {:value 100}] 300 120)))))

(deftest chart-points-scales-to-viewbox-test
  (is (= [[0 120] [150 60] [300 0]]
         (sut/chart-points [{:value 0} {:value 50} {:value 100}] 300 120))))

;; -- path builders -----------------------------------------------------

(deftest line-path-empty-test
  (is (= "" (sut/line-path []))))

(deftest line-path-test
  (is (= "M 0,10 L 5,20" (sut/line-path [[0 10] [5 20]]))))

(deftest area-path-closes-to-floor-test
  (is (= "M 0,10 L 5,20 L 10,10 L 0,10 Z"
         (sut/area-path [[0 10] [5 20]] 10 10))))

;; -- formatting ----------------------------------------------------

(deftest fmt-usd-test
  (is (= "$100.00" (sut/fmt-usd 100)))
  (is (= "-$50.50" (sut/fmt-usd -50.5)))
  (is (= "$0.00" (sut/fmt-usd nil))))
