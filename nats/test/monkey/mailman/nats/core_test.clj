(ns monkey.mailman.nats.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [config.core :as cc]
            [monkey.mailman.core :as mc]
            [monkey.mailman.nats.core :as sut]
            [monkey.nats.core :as nc]
            [monkey.nats.jetstream.mgmt :as jsm]))

(defn- add-creds [conf]
  (let [creds (:nats-creds cc/env)]
    (cond-> conf
      (fs/exists? creds) (assoc :credential-path creds)
      (and (some? creds) (not (fs/exists? creds))) (assoc :static-creds creds))))

(defn- wait-until [pred timeout]
  (let [start (System/currentTimeMillis)]
    (loop [] 
      (if-let [p (pred)]
        p
        (if (> (System/currentTimeMillis) (+ start timeout))
          ::timeout
          (do
            (Thread/sleep 100)
            (recur)))))))

(deftest nats-broker
  (let [nats (->  {:urls [(:nats-url cc/env)]
                   :secure? true}
                  (add-creds)
                  (sut/connect))
        broker (sut/make-broker nats {:subject "test.mailman"})]
    
    (testing "broker is event poster"
      (is (satisfies? mc/EventPoster broker)))

    (testing "broker is event receiver"
      (is (satisfies? mc/EventReceiver broker)))

    (testing "with stream"
      (let [evt {:type ::test
                 :message "test event"}
            mgmt (jsm/make-mgmt nats)
            stream-id "mailman-poller"
            consumer-id "test-consumer"
            subject "test.mailman.poller"
            stream (jsm/add-stream mgmt {:name stream-id
                                         :storage-type :file
                                         :subjects [subject]})
            broker (sut/make-broker nats {:stream stream-id
                                          :consumer consumer-id
                                          :subject subject})]
        (is (some? (jsm/make-consumer mgmt stream {:name consumer-id
                                                   :ack-policy :none
                                                   :filter-subjects [subject]})))
        
        (testing "receives events by listener"
          ;; Post event before setting up the receiver to test persistence
          (is (some? (mc/post-events broker [evt])))
          (let [recv (atom [])
                handler (fn [evt]
                          (swap! recv conj evt)
                          nil)
                l (mc/add-listener broker {:stream stream-id
                                           :consumer consumer-id
                                           :subject subject
                                           :handler handler})]
            (is (some? l))
            (is (not= ::timeout (wait-until #(not-empty @recv) 1000)))
            (is (= [evt] @recv))
            (is (true? (mc/unregister-listener l)))))
        
        (testing "can post to subject"
          (is (= [evt] (mc/post-events broker [evt]))))

        (testing "can poll events from subject"
          (is (= [evt] (mc/poll-events broker 1))))

        (is (nil? (.close broker)))
        (is (true? (jsm/delete-stream mgmt stream)))))

    (testing "can listen to events on subject"
      (let [recv (promise)
            handler (fn [evt]
                      (deliver recv evt)
                      nil)
            subject "test.mailman.events"
            l (mc/add-listener broker {:subject subject
                                       :handler handler})
            evt {:type ::test
                 :message "other event"
                 :subject subject}]
        (is (satisfies? mc/Listener l))
        (is (some? (mc/post-events broker [evt])))
        (is (= evt (deref recv 1000 :timeout)))))

    (testing "uses custom serializer"
      (let [recv (promise)
            handler (fn [evt]
                      (deliver recv evt)
                      nil)
            subject "test.mailman.events-2"
            broker (sut/make-broker nats
                                    {:subject subject
                                     :serializer (constantly (.getBytes "custom-serializer"))
                                     :deserializer
                                     (fn [msg]
                                       (let [r (slurp (.getData msg))]
                                         (if (= "custom-serializer" r)
                                           {:type ::ok}
                                           {:type ::failed
                                            :contents r})))})
            l (mc/add-listener broker
                               {:subject subject
                                :handler handler})
            evt {:type ::test
                 :message "other event"
                 :subject subject}]
        (is (satisfies? mc/Listener l))
        (is (some? (mc/post-events broker [evt])))
        (is (= {:type ::ok}
               (deref recv 1000 :timeout)))))
    
    (testing "re-posts results from handlers"
      (let [recv (promise)]
        (is (some? (mc/add-listener broker
                                    {:subject "test.mailman.first"
                                     :handler (fn [evt]
                                                [{:result [{:type ::test
                                                            :message (str "reply to " (:message evt))
                                                            :subject "test.mailman.second"}]}])})))
        (is (some? (mc/add-listener broker
                                    {:subject "test.mailman.second"
                                     :handler (fn [evt]
                                                (deliver recv evt)
                                                nil)})))
        (is (some? (mc/post-events broker
                                   [{:type ::test
                                     :message "first event"
                                     :subject "test.mailman.first"}])))
        (is (= "reply to first event"
               (-> (deref recv 1000 :timeout)
                   :message)))))

    (testing "invokes each listener per event"
      (let [queue "test-queue"
            subj "test.mailman.queue"
            recv (atom {})
            make-listener (fn [id]
                            (fn [msg]
                              (swap! recv update id (fnil conj []) msg)
                              nil))
            listeners (->> [:a :b]
                           (map make-listener)
                           (map (fn [l]
                                  (mc/add-listener broker {:subject subj
                                                           :queue queue
                                                           :handler l})))
                           (doall))
            n 10]
        (is (= n (->> (range n)
                      (map (fn [i]
                             {:subject subj
                              :type ::test-event
                              :idx i}))
                      (mc/post-events broker)
                      (count))))
        (is (not (= ::timeout (wait-until #(<= n (count (flatten (vals @recv)))) 10000))))
        (let [all (->> @recv
                       vals
                       flatten
                       (map :idx))]
          (is (= 20 (count all))
              "each event should be handled twice"))))

    (testing "passes queue to subscription"
      (let [opts (atom nil)]
        (with-redefs [nc/subscribe (fn [_ _ _ o]
                                     (reset! opts o)
                                     ::test-sub)]
          (is (some? (mc/add-listener broker {:subject "test.subject"
                                              :queue "test-queue"
                                              :handler (constantly nil)})))
          (is (= "test-queue" (:queue @opts))))))

    (is (nil? (.close broker)))
    (is (nil? (.close nats)))))
