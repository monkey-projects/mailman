(ns monkey.mailman.mem
  "In-memory implementation of mailman protocols, primarily intended for development 
   and testing purposes."
  (:require [monkey.mailman
             [core :as mc]
             [utils :as u]])
  (:import [java.util.concurrent ConcurrentLinkedQueue]))

(defrecord Listener [handler unregister-fn]
  mc/Listener
  (invoke-listener [_ evt]
    (handler evt))
  (unregister-listener [this]
    (unregister-fn this)))

(defn- post-results
  "Posts back any events that are the result of a previous listener invocation"
  [broker res]
  (let [events (->> (map :result res)
                    (flatten)
                    (remove nil?))]
    (when-not (empty? events)
      (mc/post-events broker events))))

(defn- maybe-start-thread! [e state]
  (when-not (:thread @state)
    (let [t (Thread. (fn []
                       ;; Runs a simple poll loop
                       (while (not-empty (:listeners @state))
                         (doseq [evt (mc/poll-events e nil)]
                           (u/invoke-and-repost evt e (:listeners @state)))
                         (Thread/sleep 100))))]
      (.start t)
      (swap! state assoc :thread t))))

(defn- unregister [state l]
  (->> (swap! state update :listeners (partial remove (partial = l)))
       (filter (partial = l))
       (empty?)))

(defrecord MemoryBroker [queue state]
  mc/EventPoster
  (post-events [this events]
    (.addAll queue events)
    events)

  mc/EventReceiver
  (poll-events [this n]
    (loop [res []]
      (if-let [evt (.poll queue)]
        (let [v (conj res evt)]
          (if (or (nil? n) (< (count v) n))
            (recur v)
            v))
        res)))

  (add-listener [this {:keys [handler]}]
    (let [l (->Listener handler (partial unregister state))]
      (swap! state update :listeners (fnil conj []) l)
      (maybe-start-thread! this state)
      l)))

(defn make-memory-broker []
  (->MemoryBroker (ConcurrentLinkedQueue.) (atom {})))

(def ^:deprecated make-memory-events make-memory-broker)
