(ns monkey.mailman.mem-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.mailman
             [core :as c]
             [mem :as sut]]))

(deftest memory-events
  (testing "posted events can be polled"
    (let [e (sut/make-memory-events)
          evt {:type ::test-evt}]
      (is (= [evt] (c/post-events e [evt])))
      (is (= 1 (count (:queue e)))
          "event should be in the queue")
      (is (= evt (c/poll-next e)))))

  (testing "listeners"
    (let [e (sut/make-memory-events)
          recv (promise)
          l (c/add-listener e (fn [evt]
                                (deliver recv evt)
                                nil))]
      (testing "can register"
        (is (some? l)))
      
      (testing "receive posted events"
        (let [evt {:type ::some-evt}]
          (is (some? (c/post-events e [evt])))
          (is (= evt (deref recv 1000 :timeout)))))

      (testing "can unregister"
        (is (true? (c/unregister-listener l))))))

  (testing "re-posts events in listener return values"
    (let [e (sut/make-memory-events)
          recv (promise)
          handle-first (fn [{:keys [event]}]
                         {:type ::second
                          :orig event})
          handle-second (fn [{:keys [event]}]
                          (deliver recv event)
                          nil)
          router (c/router {::first [handle-first]
                            ::second [handle-second]})
          l (c/add-listener e router)]
      (is (some? (c/post-events e [{:type ::first}])))
      (is (= ::second (-> (deref recv 1000 :timeout) :type))))))
