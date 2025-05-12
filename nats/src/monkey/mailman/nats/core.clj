(ns monkey.mailman.nats.core
  (:require [clojure.tools.logging :as log]
            [monkey.mailman
             [core :as mc]
             [utils :as mu]]
            [monkey.nats
             [core :as nc]
             [jetstream :as js]]))

(deftype State [state])

(defn- from-state [state path]
  (get-in @(.state state) path))

(defn- update-state [state f & args]
  (apply swap! (.state state) f args))

(defn- get-jetstream [state conn]
  (if-let [js (from-state state [:jetstream])]
    js
    (let [js (js/make-jetstream conn)]
      (update-state state assoc :jetstream js)
      js)))

(defn- get-consumer-ctx [state conn {:keys [stream consumer]}]
  (if-let [ctx (from-state state [:consumer-ctx stream consumer])]
    ctx
    (let [js (get-jetstream state conn)
          ctx (js/consumer-ctx js stream consumer)]
      (log/debug "Creating consumer context for" stream consumer)
      (update-state state assoc-in [:consumer-ctx stream consumer] ctx)
      ctx)))

(defn- set-fetcher [state fetcher]
  (update-state state assoc :fetcher fetcher))

(defn- get-fetcher [state conn conf]
  (if-let [f (from-state state [:fetcher])]
    f
    (let [ctx (get-consumer-ctx state conn conf)
          f (js/fetch ctx (-> (select-keys conf [:deserializer])
                              (merge (:poll-opts conf))))]
      (set-fetcher state f)
      f)))

(defn- close-fetcher [state]
  (swap! (.state state) (fn [{:keys [fetcher] :as s}]
                          (when fetcher
                            (.close fetcher))
                          (dissoc s :fetcher))))

(defn- register-subscription [state id s]
  (update-state state assoc-in [:subscriptions id] s))

(defn- unregister-subscription [state id]
  (when-let [s (from-state state [:subscriptions id])]
    (.unsubscribe s)
    (update-state state update :subscriptions dissoc id)
    true))

(defn- register-consumer [state id s]
  (update-state state assoc-in [:consumers id] s))

(defn- unregister-consumer [state id]
  (when-let [c (from-state state [:consumers id])]
    (.stop c)
    (.close c)
    (update-state state update :consumers dissoc id)
    true))

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
    (unregister-subscription state id)))

(defn- subscribe
  "Sets up a subscription listener, without persistence."
  [{:keys [nats state] :as broker} {:keys [subject handler] :as opts}]
  (let [l (->SubscriptionListener (random-uuid) state handler)
        sub (nc/subscribe nats
                          subject
                          (fn [evt]
                            (mu/invoke-and-repost evt broker [l]))
                          (merge default-subscriber-opts
                                 (select-keys opts [:queue :deserializer])))]
    (register-subscription state (:id l) sub)
    l))

(defrecord ConsumerListener [id state handler]
  mc/Listener
  (invoke-listener [this evt]
    (handler evt))

  (unregister-listener [this]
    (unregister-consumer state id)))

(defn- consume
  "Sets up a consumer listener, using JetStream.  This is when a stream and a
   consumer is configured when adding a listener."
  [{:keys [state] :as broker} opts]
  (let [l (->ConsumerListener (random-uuid) state (:handler opts))
        ctx (get-consumer-ctx state (:nats broker) opts)
        conf (merge default-subscriber-opts
                    (select-keys opts [:deserializer])
                    (:consumer-opts opts))
        co (js/consume ctx
                       (fn [evt]
                         (log/trace "Received on stream" (select-keys opts [:stream :consumer]) ":" evt)
                         (mu/invoke-and-repost evt broker [l]))
                       conf)]
    (register-consumer state (:id l) co)
    l))

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
    (let [fetcher (get-fetcher state nats (merge default-subscriber-opts config))]
      (repeatedly n fetcher)))

  (add-listener [this opts]
    (let [stream (or (:stream opts) (:stream config))
          cid (or (:consumer opts) (:consumer config))]
      (if (and stream cid)
        (consume this (assoc opts :stream stream :consumer cid))
        (subscribe this opts))))

  java.lang.AutoCloseable
  (close [this]
    (close-fetcher state)
    nil))

(defn make-broker [nats conf]
  (->NatsBroker nats conf (->State (atom {}))))

(def connect "Just a shortcut to the nats `make-connection` function"
  nc/make-connection)
