(ns monkey.mailman.nats.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clj-nats-async.core :as nats]
            [com.stuartsierra.component :as co]
            [config.core :as cc]
            [manifold.deferred :as md]
            [monkey.mailman.core :as mc]
            [monkey.mailman.nats.core :as sut]))

(defn- as-file [creds]
  (if (fs/exists? creds)
    creds
    (let [p (-> (fs/create-temp-file)
                (fs/delete-on-exit))]
      (spit (fs/file p) creds)
      (str p))))

(deftest nats-broker
  (let [nats (nats/create-nats {:urls [(:nats-url cc/env)]
                                :credential-path (as-file (:nats-creds cc/env))
                                :secure? true})
        broker (-> (sut/make-broker nats {:subject "test.mailman"})
                   (co/start))]
    
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

    (testing "re-posts results from handlers")

    (is (some? (co/stop broker)))
    (is (nil? (.close nats)))))
