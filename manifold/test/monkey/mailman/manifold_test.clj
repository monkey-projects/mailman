(ns monkey.mailman.manifold-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold
             [deferred :as md]
             [stream :as ms]]
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
          l (mc/add-listener broker {:handler
                                     (fn [evt]
                                       (ms/put! recv evt)
                                       nil)})
          evt {:type ::for-listener}]
      (is (some? l))
      
      (testing "passes received events to listener"
        (is (some? (mc/post-events broker [evt])))
        (is (= evt (deref (ms/take! recv) 1000 :timeout))))

      (testing "can unregister listener"
        (is (true? (mc/unregister-listener l)))
        (is (some? (mc/post-events broker [evt])))
        (is (= :timeout (deref (ms/take! recv) 100 :timeout))))

      (testing "cannot add listener when broker has been stopped"
        (is (nil? (sut/stop-broker broker)))
        (is (thrown? Exception (mc/add-listener broker (constantly nil)))))))

  (testing "posts returned events"
    (let [broker (sut/manifold-broker)
          recv (promise)
          routes [[::first [{:handler (constantly [{:type ::second}])}]]
                  [::second [{:handler (fn [ctx]
                                         (deliver recv (:event ctx))
                                         nil)}]]]
          router (mc/make-router routes)
          l (mc/add-listener broker {:handler router})]
      (is (some? (mc/post-events broker [{:type ::first}])))
      (is (= ::second (-> (deref recv 100 :timeout)
                          :type))))))

(deftest async-invoker
  (testing "invokes each of the handlers async"
    (let [handlers (->> (range)
                        (map (fn [idx]
                               #(md/future (str "handled by " idx ": " %))))
                        (take 3))
          res (sut/async-invoker handlers ::test-event)]
      (is (md/deferred? res))
      (is (= (count handlers) (count @res)))
      (is (every? (partial re-matches #"handled by \d+: .*") @res)))))
