(ns monkey.mailman.pedestal-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.mailman
             [interceptors :as mmi]
             [pedestal :as sut]]))

(deftest interceptor-chain
  (testing "executes interceptors"
    (let [i {:name ::test-interceptor
             :enter (fn [ctx]
                      (assoc ctx ::enter true))
             :leave (fn [ctx]
                      (assoc ctx ::leave true))}
          c (sut/interceptor-chain [i])
          r (mmi/execute c mmi/empty-context)]
      (is (some? r))
      (is (true? (::enter r)))
      (is (true? (::leave r))))))
