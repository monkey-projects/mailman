(ns monkey.mailman.sieppari-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.mailman
             [interceptors :as mmi]
             [sieppari :as sut]]))

(deftest execute
  (testing "executes interceptors"
    (let [i {:name ::test-interceptor
             :enter (fn [ctx]
                      (assoc ctx ::enter true))
             :leave (fn [ctx]
                      (assoc ctx ::leave true))}
          r (sut/execute [i] mmi/empty-context)]
      (is (some? r))
      (is (true? (::enter r)))
      (is (true? (::leave r))))))
