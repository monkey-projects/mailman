(ns monkey.mailman.nats.state-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.mailman.nats.state :as sut]
            [monkey.nats.core :as nc]))

(deftest state
  (let [state (sut/make-state)]
    (testing "can update and fetch path"
      (is (= {:key "value"}
             (sut/update-state state assoc :key "value"))
          "returns updated state contents")
      (is (= "value" (sut/get-path state [:key]))
          "can get value")
      (is (= {:key "value"}
             @(.state state))
          "state is updated"))

    (testing "`get-or-add`"
      (let [v (atom 0)
            f #(swap! v inc)]
        (testing "adds when not existing"
          (is (= 1 (sut/get-or-add state [:n] f)))
          (is (= 1 (sut/get-path state [:n]))))
        
        (testing "retrieves existing"
          (is (= 1 (sut/get-or-add state [:n] f))))))

    (testing "`update-path` passes value at path to `f`"
      (is (= 2 (-> (sut/update-path state [:n] inc)
                   :n)))
      (is (= 2 (sut/get-path state [:n]))))))

(deftest subscriptions
  (let [state (sut/make-state)
        opts {:subject "test-subject"}]

    (testing "`subscribe`"
      (testing "creates new subscription with listener"
        (let [listener {:id ::listener-1}
              maker-args (atom nil)]
          (is (= listener (sut/subscribe state listener opts (fn [arg]
                                                               (reset! maker-args arg)
                                                               ::subscription))))
          (is (map? (sut/get-sub state opts)))
          (is (fn? @maker-args))
          (is (= [listener] (@maker-args)))))

      (testing "when same subject and options, adds listener to existing subscription"
        (let [other-list {:id ::listener-2}]
          (is (= other-list (sut/subscribe state other-list opts (constantly nil))))
          (is (= #{::listener-1 ::listener-2}
                 (->> (sut/get-sub state opts)
                      :listeners
                      keys
                      set)))
          (is (= ::subscription (:sub (sut/get-sub state opts)))))))

    (testing "`unsubscribe`"
      (testing "can unsubscribe single listener"
        (is (true? (sut/unsubscribe state ::listener-1)))
        (is (= [::listener-2]
               (->> (sut/get-sub state opts)
                    :listeners
                    keys))))

      (testing "unsubscribes from server when no more listeners"
        (let [unsub? (atom false)]
          (with-redefs [nc/unsubscribe (fn [_] (reset! unsub? true))]
            (is (true? (sut/unsubscribe state ::listener-2)))
            (is (true? @unsub?)))))

      (testing "false when listener not found"
        (is (false? (sut/unsubscribe state ::listener-3)))))))

(defrecord TestConsumer [stopped? closed?]
  io.nats.client.MessageConsumer
  (stop [this]
    (reset! stopped? true))

  (close [this]
    (reset! closed? true)))

(deftest consumptions
  (let [state (sut/make-state)
        opts {:subject "test-subject"}
        msg-cons (->TestConsumer (atom false) (atom false))]

    (testing "`register-consumer`"
      (testing "creates new subscription with listener"
        (let [listener {:id ::listener-1}
              maker-args (atom nil)]
          (is (= listener (sut/register-consumer state listener opts (fn [arg]
                                                                       (reset! maker-args arg)
                                                                       msg-cons))))
          (is (map? (sut/get-cons state opts)))
          (is (fn? @maker-args))
          (is (= [listener] (@maker-args)))))

      (testing "when same subject and options, adds listener to existing subscription"
        (let [other-list {:id ::listener-2}]
          (is (= other-list (sut/register-consumer state other-list opts (constantly nil))))
          (is (= #{::listener-1 ::listener-2}
                 (->> (sut/get-cons state opts)
                      :listeners
                      keys
                      set)))
          (is (= msg-cons (:sub (sut/get-cons state opts)))))))

    (testing "`unregister-consumer`"
      (testing "can unregister single listener"
        (is (true? (sut/unregister-consumer state ::listener-1)))
        (is (= [::listener-2]
               (->> (sut/get-cons state opts)
                    :listeners
                    keys))))

      (testing "stops consuming when no more listeners"
        (is (true? (sut/unregister-consumer state ::listener-2)))
        (is (true? @(:closed? msg-cons)))
        (is (true? @(:stopped? msg-cons))))

      (testing "false when listener not found"
        (is (false? (sut/unregister-consumer state ::listener-3)))))))
