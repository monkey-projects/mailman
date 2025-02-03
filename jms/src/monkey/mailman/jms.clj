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

(defn- producer-opts [config]
  (merge {:serializer serialize} config))

(defn- consumer-opts [config]
  (merge {:deserializer deserialize} config))

(defn- set-listener
  "Sets the listener on the consumer.  If this is set, it's no longer possible to
   poll for events."
  [consumer l]
  (jms/set-listener consumer l))

(defn- get-consumer
  "Retrieves consumer from the state, or creates one"
  [state dest maker]
  (or (get-in @state [:consumers dest])
      (do
        (swap! state assoc-in [:consumers dest] (maker dest))
        (get-consumer state dest maker))))

(defn- get-producer
  "Retrieves producer from the state, or creates one"
  [{:keys [context config state] :as broker} dest]
  (let [dest (or dest (:destination config))]
    (if-let [p (get-in @state [:producers dest])]
      p
      (do
        (log/debug "Creating producer for" dest)
        (let [p (jms/make-producer context dest (producer-opts config))]
          (swap! state assoc-in [:producers dest] p)
          p)))))

(defn- post [broker [dest msgs :as evt]]
  (if-let [dest (or dest (get-in broker [:config :destination]))]
    (let [p (get-producer broker dest)]
      (map (fn [msg]
             (p msg)
             msg)
           msgs))
    (log/warn "No destination configured for these events:" msgs)))

(defrecord Listener [id handler destination state]
  c/Listener
  (invoke-listener [this evt]
    (handler evt))
  
  (unregister-listener [this]
    (some? (when (contains? (get-in @state [:listeners destination]) id)
             (swap! state update-in [:listeners destination] dissoc id)))))

(defn- dispatch-evt [broker dest msg]
  (let [ld (-> broker :state deref (get-in [:listeners dest]))
        evt (deserialize msg)]
    (when (not-empty ld)
      (log/trace "Dispatching event to" (count ld) "listeners:" evt)
      (try
        (doseq [l (vals ld)]
          (try
            (let [r (->> (c/invoke-listener l evt)
                         (map :result)
                         (flatten)
                         (remove nil?))]
              (log/trace "Posting result:" r)
              (c/post-events broker r))
            (catch Exception ex
              (log/error "Failed to handle event" ex))))
        (catch Throwable ex
          (log/error "Unable to dispatch" ex))))))

(defn- register-listener
  "Registers a new listener in the state.  If there is no consumer yet for the destination 
   configured in the listener, one is created."
  [state {:keys [context config] :as broker} {dest :destination :as listener}]
  (letfn [(make-consumer [dest]
            (log/debug "Creating consumer for" dest)
            (-> (jms/make-consumer context dest (consumer-opts config))
                (set-listener (partial dispatch-evt broker dest))))
          (maybe-create-consumer [state]
            (if-let [c (get-in state [:consumers dest])]
              (do
                (set-listener c (partial dispatch-evt broker dest))
                state)
              (assoc-in state [:consumers dest] (make-consumer dest))))]
    (-> state
        (assoc-in [:listeners dest (:id listener)] listener)
        (maybe-create-consumer))))

(defn- group-by-dest [{:keys [destination destination-mapper]} events]
  (group-by (or destination-mapper (constantly destination))
            events))

(defrecord JmsBroker [context config state]
  c/EventPoster
  (post-events [this events]
    (->> events
         (group-by-dest config)
         (mapcat (partial post this))
         (doall)))

  c/EventReceiver
  (poll-events [this n]
    ;; Only works if a global destination is configured
    (if-let [dest (:destination config)]
      (let [consumer (get-consumer state
                                   dest
                                   #(jms/make-consumer context % (consumer-opts config)))]
        (->> (repeatedly #(consumer 0))
             (take-while some?)
             (take n)
             (doall)))
      (throw (ex-info "No global destination configured on broker" config))))

  (add-listener [this l]
    (let [listener (-> (if (fn? l)
                         {:handler l
                          :destination (:destination config)}
                         (update l :destination #(or % (:destination config))))
                       (assoc :id (random-uuid)
                              :state state)
                       (map->Listener))]
      (swap! state register-listener this listener)
      listener)))

(defn jms-broker
  "Creates an event broker uses JMS"
  [config]
  (let [ctx (jms/connect config)]
    (->JmsBroker
     ctx
     config
     (atom {}))))

(defn disconnect
  "Closes the connection to the JMS broker"
  [broker]
  (jms/disconnect (:context broker)))
