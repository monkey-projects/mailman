(ns monkey.mailman.jms
  (:require [clojure.tools.logging :as log]
            [monkey.mailman.core :as c]
            [monkey.jms :as jms]))

(defn- post [producer msg]
  (when (@producer msg)
    msg))

(defrecord JmsBroker [context producer consumer]
  c/EventPoster
  (post-events [this events]
    (->> events
         (map (partial post producer))
         (doall)))

  c/EventReceiver
  (poll-events [this n]
    (log/debug "Polling for" n "events")
    (->> (repeatedly #(@consumer 0))
         (take-while some?)
         (take n)
         (doall)))

  (add-listener [this l]))

(defn- serialize [ctx evt]
  (->> (pr-str evt)
       (jms/make-text-message ctx)))

(defn jms-broker
  "Creates an event broker uses JMS"
  [{:keys [destination] :as config}]
  (let [ctx (jms/connect config)]
    (->JmsBroker
     ctx
     ;; Lazy initialization because we don't know if we'll need them
     (delay (jms/make-producer ctx destination {:serializer serialize}))
     (delay (jms/make-consumer ctx destination)))))

(defn disconnect
  "Closes the connection to the JMS broker"
  [broker]
  (jms/disconnect (:context broker)))
