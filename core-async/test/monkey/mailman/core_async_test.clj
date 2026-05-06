(ns monkey.mailman.core-async-test
  (:require [clojure.core.async :as ca]
            [clojure.test :refer [deftest testing is]]
            [monkey.mailman
             [core :as mc]
             [core-async :as sut]]))

(defn- wait-until [p & [timeout]]
  (let [s (System/currentTimeMillis)
        t (or timeout 1000)]
    (loop [v (p)]
      (if v
        v
        (if (> (- (System/currentTimeMillis) s) t)
          ::timeout
          (do
            (Thread/sleep 100)
            (recur (p))))))))

(deftest core-async-broker
  (let [broker (sut/core-async-broker)]
    (testing "can post and poll events"
      (let [evt {:type ::test-event}]
        (is (= [evt] (mc/post-events broker [evt])))
        (is (= evt (wait-until #(mc/poll-next broker))))))

    (let [recv (ca/chan 10)
          l (mc/add-listener broker {:handler
                                     (fn [evt]
                                       (ca/put! recv evt)
                                       nil)})
          evt {:type ::for-listener}]
      (is (some? l))

      (testing "passes received events to listener"
        (is (some? (mc/post-events broker [evt])))
        (is (= evt (ca/alt!! recv ([v] v)
                             (ca/timeout 1000) :timeout))))

      (testing "can unregister listener"
        (is (true? (mc/unregister-listener l)))
        (is (some? (mc/post-events broker [evt])))
        (is (= :timeout (ca/alt!! recv ([v] v)
                                  (ca/timeout 100) :timeout))))

      (testing "stops cleanly"
        (is (nil? (sut/stop-broker broker))))))

  (testing "posts returned events"
    (let [broker (sut/core-async-broker)
          recv (promise)
          routes [[::first [{:handler (constantly [{:type ::second}])}]]
                  [::second [{:handler (fn [ctx]
                                         (deliver recv (:event ctx))
                                         nil)}]]]
          router (mc/make-router routes {:executor (fn [i ctx]
                                                     ((:leave (last i)) ctx))})
          l (mc/add-listener broker {:handler router})]
      (is (some? (mc/post-events broker [{:type ::first}])))
      (is (= ::second (-> (deref recv 1000 :timeout)
                          :type))))))

(deftest async-invoker
  (testing "invokes each of the handlers async"
    (let [handlers (->> (range)
                        (map (fn [idx]
                               (fn [evt]
                                 (str "handled by " idx ": " evt))))
                        (take 3))
          res-ch (sut/async-invoker handlers ::test-event)
          res (ca/alt!! res-ch ([v] v)
                        (ca/timeout 1000) :timeout)]
      (is (not= :timeout res))
      (is (= (count handlers) (count res)))
      (is (every? (partial re-matches #"handled by \d+: .*") res)))))
