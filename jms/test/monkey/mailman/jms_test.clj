(ns monkey.mailman.jms-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [monkey.mailman
             [core :as mc]
             [jms :as sut]])
  (:import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ
           org.apache.activemq.artemis.core.config.impl.ConfigurationImpl
           org.apache.activemq.artemis.api.core.TransportConfiguration
           org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptorFactory))

(def broker-port 61617)
(def url (format "amqp://localhost:%d" broker-port))
(def topic "topic://test.local")

(def transport-config
  (TransportConfiguration.
   (.getName NettyAcceptorFactory)
   {"port" (str broker-port)
    "protocols" "AMQP"}))

(defn start-broker
  "Starts an embedded Artemis broker with AMQP connector"
  []
  (doto (EmbeddedActiveMQ.)
    (.setConfiguration
     (.. (ConfigurationImpl.)
         (setPersistenceEnabled false)
         (setJournalDirectory "target/data/journal")
         (setSecurityEnabled false)
         (addAcceptorConfiguration transport-config)))
    (.start)))

(defn stop-broker [b]
  (.stop b))

(defn with-broker [f]
  (let [b (start-broker)]
    (try
      (f)
      (finally 
        (stop-broker b)))))

(use-fixtures :once with-broker)

(defn- wait-until
  ([pred timeout]
   (let [interval 100]
     (loop [elapsed 0]
       (if-let [v (pred)]
         v
         (if (> elapsed timeout)
           ::timeout
           (do
             (Thread/sleep interval)
             (recur (+ elapsed interval))))))))
  ([pred]
   (wait-until pred 1000)))

(deftest jms-broker
  (let [broker (sut/jms-broker {:url url
                                :destination topic})]
    
    (testing "can post and poll events"
      (let [evt {:type ::test-event}]
        (is (= [evt] (mc/post-events broker [evt])))
        (is (= [evt] (wait-until #(mc/poll-next broker))))))))
