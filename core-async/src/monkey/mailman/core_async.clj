(ns monkey.mailman.core-async
  "Provides functionality for async event handling using [core.async](https://clojure.github.io/core.async/),
   including an in-memory implementation of a broker.  This is similar to
   the one provided in the core lib and is mainly useful for testing/development
   purposes."
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.mailman
             [core :as mmc]
             [utils :as u]]))

(set! *warn-on-reflection* true)

(declare start-broker)

(deftype Listener [id handler listeners]
  mmc/Listener
  (invoke-listener [this evt]
    (handler evt))

  (unregister-listener [this]
    (if (contains? @listeners id)
      (do
        (swap! listeners dissoc id)
        true)
      false)))

(deftype CoreAsyncBroker [chan listeners]
  mmc/EventPoster
  (post-events [this events]
    ;; Put each event onto the channel; false keeps the channel open after all items are put.
    ;; ca/onto-chan!! blocks until all items are enqueued, ensuring poll-events can see them immediately.
    (ca/onto-chan!! chan events false)
    events)

  mmc/EventReceiver
  (poll-events [this n]
    (loop [res []]
      (if-let [evt (ca/poll! chan)]
        (let [v (conj res evt)]
          (if (or (nil? n) (< (count v) n))
            (recur v)
            v))
        res)))

  (add-listener [this l]
    (let [handler (if (fn? l)
                    (do
                      (log/warn "Registered listener using deprecated param, use {:handler l} instead: " l)
                      l)
                    (:handler l))
          w ^Listener (->Listener (random-uuid) handler listeners)]
      (when (empty? @listeners)
        (start-broker this))
      (swap! listeners assoc (.id w) w)
      w)))

(defn start-broker
  "Starts a go-loop that reads events from the broker channel and dispatches
   them to all registered listeners."
  [^CoreAsyncBroker broker]
  (ca/go-loop []
    (when-let [evt (ca/<! (.chan broker))]
      (u/invoke-and-repost evt broker (vals @(.listeners broker)))
      (recur))))

(defn stop-broker
  "Closes the broker channel, which terminates the go-loop."
  [^CoreAsyncBroker broker]
  (ca/close! (.chan broker)))

(defn core-async-broker
  "Creates a new core.async broker with an optional buffer size (default: 10)."
  [& {:keys [channel buf-size]
      :or {buf-size 10}}]
  (->CoreAsyncBroker (or channel (ca/chan buf-size)) (atom {})))

(defn async-invoker
  "Routing invoker that dispatches each handler in its own go-block, collecting
   all results into a single channel.  Returns a channel that will yield a vector
   of all handler results."
  [handlers evt]
  (let [results (ca/merge (map (fn [h]
                                 (ca/go (h evt)))
                               handlers))]
    (ca/into [] results)))

(defn get-channel
  "Retrieves the channel configured on the broker"
  [^CoreAsyncBroker m]
  (.chan m))
