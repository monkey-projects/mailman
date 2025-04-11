(ns monkey.mailman.nats.core
  (:require [clj-nats-async.core :as nats]
            [manifold.stream :as ms]
            [monkey.mailman
             [core :as mc]
             [utils :as mu]]))

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

(defn- publish [nats subj evt]
  (nats/publish nats subj evt)
  evt)

(defrecord NatsListener [id stream handler]
  mc/Listener
  (invoke-listener [this evt]
    (handler (nats/msg-body evt)))

  (unregister-listener [this]
    (ms/close! stream)))

(defn- get-subject [broker evt]
  (let [{sm :subject-mapper s :subject} (:config broker)]
    (or (:subject evt)
        (when sm (sm evt))
        s)))

(defrecord NatsBroker [nats config state]
  mc/EventPoster
  (post-events [this evts]
    (->> evts
         (map (fn [evt]
                (publish nats (get-subject this evt) evt)))
         (doall)))
  
  mc/EventReceiver
  (poll-events [this n]
    (letfn [(make-poller []
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
    (let [s (nats/subscribe nats subject (select-keys opts [:queue]))
          l (->NatsListener (random-uuid) s handler)]
      (ms/consume (fn [evt]
                    (mu/invoke-and-repost evt this [l]))
                  s)
      l))

  java.lang.AutoCloseable
  (close [this]
    (close-poller state)
    nil))

(defn make-broker [nats conf]
  (->NatsBroker nats conf (->State (atom {}))))

(def connect "Just a shortcut to the nats `create-nats` function"
  nats/create-nats)
