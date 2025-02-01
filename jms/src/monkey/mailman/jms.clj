(ns monkey.mailman.jms
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [monkey.mailman.core :as c]
            [monkey.jms :as jms])
  (:import [java.io PushbackReader StringReader]))

(defn- serialize [ctx evt]
  (->> (pr-str evt)
       (jms/make-text-message ctx)))

(defn- parse-edn [s]
  (with-open [r (PushbackReader. (StringReader. s))]
    (edn/read r)))

(def deserialize
  (comp parse-edn jms/message->str))

(defn- post [producer msg]
  (when (@producer msg)
    msg))

(defrecord Listener [id handler listeners]
  c/Listener
  (invoke-listener [this evt]
    (handler evt))
  
  (unregister-listener [this]
    (some? (when (contains? @listeners id)
             (swap! listeners dissoc id)))))

(defn- dispatch-evt [listeners msg]
  (let [ld @listeners
        evt (deserialize msg)]
    (when (not-empty ld)
      (log/debug "Dispatching event to" (count ld) "listeners:" evt)
      (doseq [l (vals ld)]
        ;; TODO Re-post resulting events
        (c/invoke-listener l evt)))))

(defn- set-listener
  "Sets the listener on the consumer.  If this is set, it's no longer possible to
   poll for events."
  [consumer l]
  ;; TODO Move this functionality into the jms lib
  (.setMessageListener (:consumer consumer) (jms/make-listener l)))

(defrecord JmsBroker [context producer consumer listeners]
  c/EventPoster
  (post-events [this events]
    (->> events
         (map (partial post producer))
         (doall)))

  c/EventReceiver
  (poll-events [this n]
    (->> (repeatedly #(@consumer 0))
         (take-while some?)
         (take n)
         (doall)))

  (add-listener [this l]
    (let [listener (->Listener (random-uuid) l listeners)]
      (when (= 1 (count (swap! listeners assoc (:id listener) listener)))
        (set-listener @consumer (partial dispatch-evt listeners)))
      listener)))

(defn jms-broker
  "Creates an event broker uses JMS"
  [{:keys [destination] :as config}]
  (let [ctx (jms/connect config)]
    (->JmsBroker
     ctx
     ;; Lazy initialization because we don't know if we'll need them
     (delay (jms/make-producer ctx destination {:serializer serialize}))
     (delay (jms/make-consumer ctx destination {:deserializer deserialize}))
     (atom {}))))

(defn disconnect
  "Closes the connection to the JMS broker"
  [broker]
  (jms/disconnect (:context broker)))
