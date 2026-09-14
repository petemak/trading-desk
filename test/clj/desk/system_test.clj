(ns desk.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [desk.system :as system]))

(deftest config-validates-real-config-edn
  (testing "the actual resources/config.edn passes schema validation"
    (let [cfg (system/config :dev)]
      (is (map? cfg))
      (is (pos-int? (get-in cfg [:http :port])))
      (is (boolean? (get-in cfg [:http :join?])))
      (is (pos-int? (get-in cfg [:engine :tick-ms]))))))

(deftest invalid-config-rejected-with-clear-error
  (testing "a config missing required keys is rejected, not silently accepted"
    (let [bad-config {:http {:port "not-a-port"}}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (system/validate-config bad-config)))
      (let [ex (try (system/validate-config bad-config)
                     (catch clojure.lang.ExceptionInfo e e))
            data (ex-data ex)]
        (is (some? data))
        (is (map? (:errors data)))
        (is (not (contains? data :join?))) ; sanity: no bogus keys leaked in
        (is (.contains (.getMessage ^Exception ex) "Invalid config")))))

  (testing "a config with a negative tick-ms is rejected"
    (let [bad-config {:http {:port 8890 :join? false}
                       :engine {:tick-ms -5}}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (system/validate-config bad-config))))))

(deftest system-starts-and-stops-cleanly
  (testing "component/start and component/stop run with no exception, and
            the engine's client-registry/loop are created on start and
            torn down on stop"
    (let [sys (system/new-system :dev)
          started (component/start sys)]
      (try
        (is (some? (get-in started [:engine :clients])))
        (is (some? (get-in started [:engine :prices])))
        (is (true? @(get-in started [:engine :running])))
        (is (some? (get-in started [:pedestal :runtime])))
        (finally
          (let [stopped (component/stop started)]
            (is (nil? (get-in stopped [:engine :clients])))
            (is (nil? (get-in stopped [:engine :running])))
            (is (nil? (get-in stopped [:pedestal :runtime])))))))))
