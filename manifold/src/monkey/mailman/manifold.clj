(ns monkey.mailman.manifold
  "Provides functionality for async event handling using [Manifold](https://github.com/clj-commons/manifold),
   including an in-memory implementation of a broker.  This is similar to
   the one provided in the core lib and is mainly useful for testing/development
   purposes."
  (:require [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.mailman
             [core :as c]
             [utils :as u]]))

(defn- take-next
  "Takes the next immediately available value from the stream, or `nil` if none is available."
  [stream]
  ;; Only take if immediately available
  @(ms/try-take! stream 0))

(deftype Listener [id handler listeners]
  c/Listener
  (invoke-listener [this evt]
    (handler evt))

  (unregister-listener [this]
    (if (contains? @listeners id)
      (do
        (swap! listeners dissoc id)
        true)
      false)))

(defn start-broker
  "Starts receiving events from the broker stream and dispatching them to the listeners."
  [broker]
  (ms/consume (fn [evt]
                (u/invoke-and-repost evt broker (vals @(.listeners broker))))
              (.stream broker)))

(defn stop-broker
  "Shuts down the broker stream"
  [broker]
  (ms/close! (.stream broker)))

(deftype ManifoldBroker [stream listeners]
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

  (add-listener [this {:keys [handler]}]
    (let [w (->Listener (random-uuid) handler listeners)]
      ;; Start broker when first listener is registered
      ;; Note that this won't have any effect if it has been stopped previously, since the
      ;; stream will have been closed then.
      (when (ms/closed? stream)
        (throw (ex-info "Cannot add listener, broker has already been stopped")))
      (when (empty? @listeners)
        (start-broker this))
      (swap! listeners assoc (.id w) w)
      w)))

(defn manifold-broker [& {:keys [buf-size]
                          :or {buf-size 10}}]
  ;; Do not auto-start, only do this when a listener is first registered
  (->ManifoldBroker (ms/stream buf-size) (atom {})))

(defn async-invoker
  "Routing invoker that assumes the handlers return a deferred value.  Invokes each
   of them in parallel and returns a deferred that will hold the results, as per 
   `manifold.deferred.zip`."
  [handlers evt]
  (->> handlers
       (map (fn [h]
              (h evt)))
       (apply md/zip)))
