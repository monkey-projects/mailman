(ns monkey.mailman.nats.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clj-nats-async.core :as nats]
            [config.core :as cc]
            [manifold.deferred :as md]
            [monkey.mailman.core :as mc]
            [monkey.mailman.nats.core :as sut]))

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

    (let [evt {:type ::test
               :message "test event"}]
      (testing "can post to subject"
        (is (= [evt] (mc/post-events broker [evt]))))
      
      #_(testing "can poll events from subject"
          ;; FIXME Need jetstream for this
          (is (= [evt] (mc/poll-events broker 1)))))

    (testing "can listen to events on subject"
      (let [recv (md/deferred)
            handler (fn [evt]
                      (md/success! recv evt)
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

    (testing "re-posts results from handlers"
      (let [recv (md/deferred)
            handler (fn [evt]
                      (md/success! recv evt)
                      nil)]
        (is (some? (mc/add-listener broker {:subject "test.mailman.first"
                                            :handler (fn [evt]
                                                       [{:result [{:type ::test
                                                                   :message "reply"
                                                                   :subject "test.mailman.second"}]}])})))
        (is (some? (mc/add-listener broker {:subject "test.mailman.second"
                                            :handler handler})))
        (is (some? (mc/post-events broker [{:type ::test
                                            :message "first event"
                                            :subject "test.mailman.first"}])))
        (is (= "reply" (-> (deref recv 1000 :timeout)
                           :message)))))

    (testing "applies queue groups"
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
          (is (= (count all)
                 (-> all
                     (distinct)
                     (count)))
              "each event should be handled only once"))))

    (is (nil? (.close broker)))
    (is (nil? (.close nats)))))
