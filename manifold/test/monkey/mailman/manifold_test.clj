(ns monkey.mailman.manifold-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.stream :as ms]
            [monkey.mailman
             [core :as mc]
             [manifold :as sut]]))

(deftest manifold-broker
  (let [broker (sut/manifold-broker)]
    (testing "can post and poll events"
      (let [evt {:type ::test-event}]
        (is (= [evt] @(mc/post-events broker [evt])))
        (is (= evt (mc/poll-next broker)))))

    (let [recv (ms/stream 10)
          l (mc/add-listener broker (fn [evt]
                                      (ms/put! recv evt)
                                      nil))
          evt {:type ::for-listener}]
      (is (some? l))
      
      (testing "passes received events to listener"
        (is (some? (mc/post-events broker [evt])))
        (is (= evt (deref (ms/take! recv) 1000 :timeout)))))))
