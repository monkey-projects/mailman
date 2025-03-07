(ns monkey.mailman.jms
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [monkey.mailman
             [core :as c]
             [utils :as u]]
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

;; Put state in a type, to hide its contents when printing, otherwise we may
;; risk stack overflow.
(deftype State [values])

(defn- state-add-consumer! [state dest c]
  (swap! (.values state) assoc-in [:consumers dest] c)
  c)

(defn- state-get-consumers [state]
  (:consumers @(.values state)))

(defn- state-get-consumer [state dest]
  (get (state-get-consumers state) dest))

(defn- state-add-producer! [state dest p]
  (swap! (.values state) assoc-in [:producers dest] p)
  p)

(defn- state-get-producers [state]
  (:producers @(.values state)))

(defn- state-get-producer [state dest]
  (get (state-get-producers state) dest))

(defn- state-get-listeners [state dest]
  (get-in @(.values state) [:listeners dest]))

(defn- state-remove-listener! [state dest id]
  (swap! (.values state) update-in [:listeners dest] dissoc id))

(defn- dispatch-evt [broker dest msg]
  (let [ld (state-get-listeners (:state broker) dest)
        evt (deserialize msg)]
    (when (not-empty ld)
      (log/trace "Dispatching event to" (count ld) "listeners:" evt)
      (try
        (u/invoke-and-repost evt broker (vals ld))
        (catch Throwable ex
          (log/error "Unable to dispatch" ex))))))

(defn- state-register-listener!
  "Registers a new listener in the state.  If there is no consumer yet for the destination 
   configured in the listener, one is created."
  [state {:keys [context config] :as broker} {dest :destination :as listener}]
  (letfn [(make-consumer [dest]
            (log/debug "Creating consumer for" dest)
            ;; TODO Allow for custom id for durable subscribers
            (-> (jms/make-consumer context dest (consumer-opts config))
                (set-listener (partial dispatch-evt broker dest))))
          (maybe-create-consumer [state]
            (if-let [c (get-in state [:consumers dest])]
              (do
                (set-listener c (partial dispatch-evt broker dest))
                state)
              (assoc-in state [:consumers dest] (make-consumer dest))))]
    (swap! (.values state)
           (fn [state]
             (-> state
                 (assoc-in [:listeners dest (:id listener)] listener)
                 (maybe-create-consumer))))))

(defn- get-consumer
  "Retrieves consumer from the state, or creates one"
  [state dest maker]
  (or (state-get-consumer state dest)
      (state-add-consumer! state dest (maker dest))))

(defn- get-producer
  "Retrieves producer from the state, or creates one"
  [{:keys [context config state] :as broker} dest]
  (let [dest (or dest (:destination config))]
    (if-let [p (state-get-producer state dest)]
      p
      (do
        (log/debug "Creating producer for" dest)
        (let [p (jms/make-producer context dest (producer-opts config))]
          (state-add-producer! state dest p))))))

(defn- post [broker [dest msgs :as evt]]
  (if-let [dest (or dest (get-in broker [:config :destination]))]
    (let [p (get-producer broker dest)]
      (map (fn [msg]
             (log/trace "Posting message to JMS broker:" msg)
             (p msg)
             msg)
           msgs))
    (log/warn "No destination configured for these events:" msgs)))

(defrecord Listener [id handler destination state]
  c/Listener
  (invoke-listener [this evt]
    (handler evt))
  
  (unregister-listener [this]
    ;; TODO Stop consuming when the last listener for a consumer has been unregistered
    (some? (when (contains? (state-get-listeners state destination) id)
             (state-remove-listener! state destination id)))))

(defn- group-by-dest [{:keys [destination destination-mapper]} events]
  (group-by (or destination-mapper (constantly destination))
            events))

(defn- close-all [closeables]
  (doseq [c closeables]
    (.close c)))

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
      (state-register-listener! state this listener)
      listener))

  java.lang.AutoCloseable
  (close [this]
    (close-all (vals (state-get-consumers state)))
    (close-all (vals (state-get-producers state)))))

(defn jms-broker
  "Creates an event broker uses JMS"
  [config]
  (let [ctx (jms/connect config)]
    (->JmsBroker
     ctx
     config
     (->State (atom {})))))

(defn disconnect
  "Closes the connection to the JMS broker"
  [broker]
  (jms/disconnect (:context broker)))
