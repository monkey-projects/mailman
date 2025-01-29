(ns monkey.mailman.mem-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.mailman
             [core :as c]
             [mem :as sut]]))

(deftest memory-events
  (testing "posted events can be pulled"
    (let [e (sut/make-memory-events)
          evt {:type ::test-evt}]
      (is (= [evt] (c/post-events e [evt])))
      (is (= 1 (count (:queue e)))
          "event should be in the queue")
      (is (= evt (c/pull-next e)))))

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

  (testing "re-posts events in listener return values"))
