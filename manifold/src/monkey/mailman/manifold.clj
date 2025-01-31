(ns monkey.mailman.manifold
  "Provides functionality for async event handling using [Manifold](https://github.com/clj-commons/manifold),
   including an in-memory implementation of a broker.  This is similar to
   the one provided in the core lib and is mainly useful for testing/development
   purposes."
  (:require [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.mailman.core :as c]))

(defn- take-next
  "Takes the next immediately available value from the stream, or `nil` if none is available."
  [stream]
  ;; Only take if immediately available
  @(ms/try-take! stream 0))

(defrecord Listener [id handler]
  c/Listener
  (invoke-listener [this evt]
    (handler evt))

  (unregister-listener [this]
    ;; TODO
    ))

(defn start-broker
  "Starts receiving events from the broker stream and dispatching them to the listeners."
  [broker]
  (ms/consume (fn [evt]
                (doseq [l (vals @(:listeners broker))]
                  (c/invoke-listener l evt)))
              (:stream broker)))

(defn stop-broker
  "Shuts down the broker stream"
  [broker]
  (ms/close! (:stream broker)))

(defrecord ManifoldBroker [stream listeners]
  c/EventPoster
  (post-events [this events]
    (md/chain
     (ms/put-all! stream events)
     (fn [ok?]
       (when ok?
         events))))

  c/EventReceiver
  (poll-events [this n]
    (->> (repeatedly #(take-next stream))
         (take n)
         (take-while some?)))

  (add-listener [this l]
    (let [w (->Listener (random-uuid) l)]
      (when (empty? @listeners)
        (start-broker this))
      (swap! listeners assoc (:id w) w)
      w)))

(defn manifold-broker [& {:keys [buf-size]
                          :or {buf-size 10}}]
  ;; Do not auto-start, only do this when a listener is first registered
  (->ManifoldBroker (ms/stream buf-size) (atom {})))
