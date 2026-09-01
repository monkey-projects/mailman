(ns monkey.mailman.mem
  "In-memory implementation of mailman protocols, primarily intended for development 
   and testing purposes."
  (:require [monkey.mailman
             [core :as mc]
             [utils :as u]]))

(defrecord Listener [handler unregister-fn]
  mc/Listener
  (invoke-listener [_ evt]
    (handler evt))
  (unregister-listener [this]
    (unregister-fn this)))

(defn- maybe-start-thread! [e state]
  (when-not (:thread @state)
    (let [t (doto (Thread. (fn []
                             ;; Runs a simple poll loop
                             (while (not-empty (:listeners @state))
                               (doseq [evt (mc/poll-events e nil)]
                                 (u/invoke-and-repost evt e (:listeners @state)))
                               (Thread/sleep 100))))
              (.setDaemon true))]
      (.start t)
      (swap! state assoc :thread t))))

(defn- unregister [state l]
  (let [p? (->> @state
                :listeners
                (filter (partial = l))
                (not-empty)
                (some?))]
    (swap! state update :listeners (partial remove (partial = l)))
    p?))

(defrecord MemoryBroker [queue state]
  mc/EventPoster
  (post-events [this events]
    (swap! queue #(reduce conj % events))
    events)

  mc/EventReceiver
  (poll-events [this n]
    (loop [res []]
      (if-let [evt (peek @queue)]
        (do
          (swap! queue pop)
          (let [v (conj res evt)]
            (if (or (nil? n) (< (count v) n))
              (recur v)
              v)))
        res)))

  (add-listener [this {:keys [handler]}]
    (let [l (->Listener handler (partial unregister state))]
      (swap! state update :listeners (fnil conj []) l)
      (maybe-start-thread! this state)
      l)))

(defn make-memory-broker []
  (->MemoryBroker (atom clojure.lang.PersistentQueue/EMPTY)
                  (atom {})))

(def ^:deprecated make-memory-events make-memory-broker)
