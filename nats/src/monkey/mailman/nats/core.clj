(ns monkey.mailman.nats.core
  (:require [clojure.tools.logging :as log]
            [monkey.mailman
             [core :as mc]
             [utils :as mu]]
            [monkey.mailman.nats.state :as s]
            [monkey.nats
             [core :as nc]
             [jetstream :as js]]))

(defn- publish [nats subj evt]
  (log/trace "Publishing to:" subj "-" evt)
  (nc/publish nats subj evt {})
  evt)

(defn- get-subject [broker evt]
  (let [{sm :subject-mapper s :subject} (:config broker)]
    (or (:subject evt)
        (when sm (sm evt))
        s)))

(def default-subscriber-opts {:deserializer nc/from-edn})

(defrecord SubscriptionListener [id state handler]
  mc/Listener
  (invoke-listener [this evt]
    (handler evt))

  (unregister-listener [this]
    (s/unsubscribe state id)))

(defn- subscribe
  "Sets up a subscription listener, without persistence.  If a subscription already
   exists for the same subject, adds it to the existing subscription listeners."
  [{:keys [nats state] :as broker} {:keys [subject handler] :as opts}]
  (let [l (->SubscriptionListener (random-uuid) state handler)
        sub-opts (-> default-subscriber-opts
                     (merge (select-keys opts [:queue :deserializer]))
                     (assoc :subject subject))
        make-sub (fn [get-list]
                   (log/debug "Registering ephemeral subscription:" opts)
                   (nc/subscribe nats
                                 subject
                                 (fn [evt]
                                   (mu/invoke-and-repost evt broker (get-list)))
                                 sub-opts))]
    (s/subscribe state l sub-opts make-sub)))

(defrecord ConsumerListener [id state handler]
  mc/Listener
  (invoke-listener [this evt]
    (handler evt))

  (unregister-listener [this]
    (s/unregister-consumer state id)))

(defn- consume
  "Sets up a consumer listener, using JetStream.  This is when a stream and a
   consumer is configured when adding a listener."
  [{:keys [state] :as broker} opts]
  (log/debug "Registering jetstream consumption:" opts)
  (let [l (->ConsumerListener (random-uuid) state (:handler opts))
        ctx (s/get-consumer-ctx state (:nats broker) opts)
        conf (merge default-subscriber-opts
                    (select-keys opts [:deserializer])
                    (:consumer-opts opts))
        make-cons (fn [get-list]
                    (js/consume
                     ctx
                     (fn [evt]
                       (log/trace "Received on stream" (select-keys opts [:stream :consumer]) ":" evt)
                       (mu/invoke-and-repost evt broker (get-list)))
                     conf))]
    (s/register-consumer state l conf make-cons)))

(defrecord NatsBroker [nats config state]
  mc/EventPoster
  (post-events [this evts]
    (->> evts
         (map (fn [evt]
                ;; TODO If a stream is configured, publish via jetstream
                (publish nats (get-subject this evt) evt)))
         (doall)))
  
  mc/EventReceiver
  (poll-events [this n]
    (let [fetcher (s/get-fetcher state nats (merge default-subscriber-opts config))]
      (repeatedly n fetcher)))

  (add-listener [this opts]
    (let [stream (or (:stream opts) (:stream config))
          cid (or (:consumer opts) (:consumer config))]
      (if (and stream cid)
        (consume this (assoc opts :stream stream :consumer cid))
        (subscribe this opts))))

  java.lang.AutoCloseable
  (close [this]
    (s/close-fetcher state)
    nil))

(defn make-broker [nats conf]
  (->NatsBroker nats conf (s/make-state)))

(def connect "Just a shortcut to the nats `make-connection` function"
  nc/make-connection)
