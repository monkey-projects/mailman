(ns monkey.mailman.jms-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [monkey.jms.artemis :as artemis]
            [monkey.mailman
             [core :as mc]
             [jms :as sut]]))

(def broker-port 61617)
(def url (format "amqp://localhost:%d" broker-port))
(def topic "topic://test.local")
(def queue "queue://test.local.queue")

(defn with-broker [f]
  (artemis/with-broker {:broker-port broker-port} f))

(use-fixtures :once with-broker)

(defn- wait-until
  ([pred timeout timeout-val]
   (let [interval 100]
     (loop [elapsed 0]
       (if-let [v (pred)]
         v
         (if (> elapsed timeout)
           (or timeout-val ::timeout)
           (do
             (Thread/sleep interval)
             (recur (+ elapsed interval))))))))
  ([pred]
   (wait-until pred 1000 nil)))

(defrecord TestCloseable [on-close]
  java.lang.AutoCloseable
  (close [this]
    (on-close this)
    nil))

(deftest jms-broker
  (let [broker (sut/jms-broker {:url url
                                :destination queue
                                :destination-mapper :destination})]
    
    (testing "can post and poll events"
      (let [evt {:type ::test-event}]
        (is (= [evt] (mc/post-events broker [evt])))
        (is (= evt (wait-until #(mc/poll-next broker))))))

    (testing "listener"
      (let [evt {:type ::other-event}
            recv (promise)
            listener (mc/add-listener
                      broker
                      (fn [evt]
                        (deliver recv evt)
                        nil))]
        (is (some? listener))

        (testing "receives events"
          (is (some? (mc/post-events broker [evt])))
          (is (= evt (deref recv 1000 :timeout))))

        (testing "can unregister"
          (is (true? (mc/unregister-listener listener))))))

    (testing "re-posts resulting events"
      (let [evt {:type ::first}
            recv (atom [])
            listener (mc/add-listener
                      broker
                      (fn [evt]
                        (swap! recv conj evt)
                        (if (= 1 (count @recv))
                          [{:result [{:type ::second}]}]
                          nil)))
            all-recv? (fn [v]
                        (when (= 2 (count v))
                          v))]
        (is (some? (mc/post-events broker [evt])))
        (is (= [::first ::second]
               (->> (wait-until #(all-recv? @recv) 1000 [::timeout])
                    (map :type))))))

    (testing "can post and listen to specific destination"
      (let [dest topic
            evt {:type ::topic-evt
                 :destination dest}
            recv (promise)
            listener (mc/add-listener
                      broker
                      {:destination dest
                       :handler (fn [evt]
                                  (deliver recv evt)
                                  nil)})]
        (is (some? (mc/post-events broker [evt])))
        (is (= evt (deref recv 1000 :timeout))))))

  (testing "closes all producers and consumers on `close`"
    (let [closed (atom #{})
          c (->TestCloseable (fn [_] (swap! closed conj :consumer)))
          p (->TestCloseable (fn [_] (swap! closed conj :producer)))
          broker (sut/map->JmsBroker {:state (sut/->State (atom {:consumers {"test-dest" c}
                                                                 :producers {"test-dest" p}}))})]
      (is (nil? (.close broker)))
      (is (= #{:consumer :producer} @closed)))))
