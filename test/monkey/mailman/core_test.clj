(ns monkey.mailman.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.mailman
             [core :as sut]
             [spec :as spec]]))

(deftest router
  (let [handled (atom [])
        handler (fn [evt]
                  (swap! handled conj evt))
        r (sut/router {::test-type [handler]})]
    (testing "creates function"
      (is (fn? r)))

    (testing "dispatches event according to type"
      (let [evt {:type ::test-type}]
        (is (s/valid? ::spec/router-result (first (r evt)))
            "returns router result structure")
        (is (= [evt] @handled))))

    (testing "`nil` when no handlers found"
      (is (nil? (r {:type ::other-type})))))

  (testing "can override route matcher"
    (let [r (sut/router {::test-type [(constantly ::ok)]}
                        {:matcher (constantly false)})]
      (is (nil? (r {:type ::test-type})))))

  (testing "invokes multiple handlers in sequence"
    (let [r (sut/router {::test-type [(constantly ::first) (constantly ::second)]})]
      (is (= [::first ::second]
             (->> (r {:type ::test-type})
                  (map :result)))))))

(deftest route-matcher
  (testing "matches by event type keyword"
    (let [routes [[::first ::test-handler]
                  [::second ::other-handler]]]
      (is (= [::other-handler] (sut/route-matcher routes {:type ::second})))
      (is (empty? (sut/route-matcher routes {:type ::third})))))

  (testing "matches maps by event type keyword"
    (let [routes {::first ::test-handler
                  ::second ::other-handler}]
      (is (= [::other-handler] (sut/route-matcher routes {:type ::second})))
      (is (empty? (sut/route-matcher routes {:type ::third}))))))
