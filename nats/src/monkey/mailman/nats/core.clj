(ns monkey.mailman.nats.core
  (:require [manifold.stream :as ms]
            [monkey.mailman
             [core :as mc]
             [utils :as mu]]
            [monkey.nats.core :as nc]))

(deftype State [state])

(defn- set-poller [state poller]
  (swap! (.state state) assoc :poller poller))

(defn- get-poller [state]
  (:poller @(.state state)))

(defn- close-poller [state]
  (swap! (.state state) (fn [{:keys [poller] :as s}]
                          (when poller
                            (ms/close! poller))
                          (dissoc s :poller))))

(defn- register-subscription [state id s]
  (swap! (.state state) assoc-in [:subscriptions id] s))

(defn- unregister-subscription [state id]
  (when-let [s (get-in @(.state state) [:subscriptions id])]
    (.unsubscribe s)
    (swap! (.state state) update :subscriptions dissoc id)))

(defn- publish [nats subj evt]
  (nc/publish nats subj evt {})
  evt)

(defrecord NatsListener [id state handler]
  mc/Listener
  (invoke-listener [this evt]
    (handler evt))

  (unregister-listener [this]
    (unregister-subscription state id)))

(defn- get-subject [broker evt]
  (let [{sm :subject-mapper s :subject} (:config broker)]
    (or (:subject evt)
        (when sm (sm evt))
        s)))

(def default-subscriber-opts {:deserializer nc/from-edn})

(defrecord NatsBroker [nats config state]
  mc/EventPoster
  (post-events [this evts]
    (->> evts
         (map (fn [evt]
                (publish nats (get-subject this evt) evt)))
         (doall)))
  
  mc/EventReceiver
  (poll-events [this n]
    #_(letfn [(make-poller []
              (let [p (nats/subscribe nats (:subject config) (select-keys config [:queue]))]
                (set-poller state p)
                p))
            (take-next [p]
              @(ms/try-take! p 0))]
      ;; FIXME This will only work when using jetstream
      (let [p (or (get-poller state)
                  (make-poller))]
        (->> (repeatedly n #(take-next p))
             (take-while some?)))))

  (add-listener [this {:keys [subject handler] :as opts}]
    (let [l (->NatsListener (random-uuid) state handler)
          sub (nc/subscribe nats
                            subject
                            (fn [evt]
                              (mu/invoke-and-repost evt this [l]))
                            (merge default-subscriber-opts
                                   (select-keys opts [:queue :deserializer])))]
      (register-subscription state (:id l) sub)
      l))

  java.lang.AutoCloseable
  (close [this]
    (close-poller state)
    nil))

(defn make-broker [nats conf]
  (->NatsBroker nats conf (->State (atom {}))))

(def connect "Just a shortcut to the nats `make-connection` function"
  nc/make-connection)
