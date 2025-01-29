(ns monkey.mailman.mem
  "In-memory implementation of mailman protocols, primarily intended for development 
   and testing purposes."
  (:require [monkey.mailman.core :as mc])
  (:import [java.util.concurrent ConcurrentLinkedQueue]))

(defrecord Listener [handler unregister-fn]
  mc/Listener
  (invoke-listener [_ evt]
    (handler evt))
  (unregister-listener [this]
    (unregister-fn this)))

(defn- maybe-start-thread! [e state]
  (when-not (:thread @state)
    (let [t (Thread. (fn []
                       ;; Runs a simple poll loop
                       (while (not-empty (:listeners @state))
                         (doseq [evt (mc/pull-events e nil)]
                           (doseq [l (:listeners @state)]
                             (try
                               (mc/invoke-listener l evt)
                               (catch Exception ignored))))
                         (Thread/sleep 100))))]
      (.start t)
      (swap! state assoc :thread t))))

(defn- unregister [state l]
  (->> (swap! state update :listeners (partial remove (partial = l)))
       (filter (partial = l))
       (empty?)))

(defrecord MemoryEvents [queue state]
  mc/EventPoster
  (post-events [this events]
    (.addAll queue events)
    events)

  mc/EventReceiver
  (pull-events [this n]
    (loop [res []]
      (if-let [evt (.poll queue)]
        (let [v (conj res evt)]
          (if (or (nil? n) (< (count v) n))
            (recur v)
            v))
        res)))

  (add-listener [this listener]
    (let [l (->Listener listener (partial unregister state))]
      (swap! state update :listeners (fnil conj []) l)
      (maybe-start-thread! this state)
      l)))

(defn make-memory-events []
  (->MemoryEvents (ConcurrentLinkedQueue.) (atom {})))
