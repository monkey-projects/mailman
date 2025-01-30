(ns monkey.mailman.core
  (:require [monkey.mailman
             [interceptors :as i]
             [spec :as spec]]))

(defprotocol EventPoster
  "Sends events to a broker."
  (post-events [this events]
    "Posts one or more events"))

(defprotocol EventReceiver
  "Receives events from a broker, either by polling or by registering a listener."
  (poll-events [this n]
    "Returns up to `n` pending events, using pull model.  If `n` is `nil`, returns all
     pending events.")

  (add-listener [this listener]
    "Registers a listener to receive incoming events"))

(defprotocol Listener
  "A listener is returned when adding a listener to a broker.  It's usually broker
   implementation specific and allows to unregister the listener."
  (invoke-listener [this evt]
    "Invokes this listener with the given event")
  (unregister-listener [this]
    "Unregisters the listener from the event receiver it was previously added to.  
     Returns `true` if it was succesfully unregistered."))

(defn poll-next
  "Polls for next event from the receiver"
  [e]
  (some-> (poll-events e 1) first))

(defprotocol ToHandler
  "Converts the object into an event handler fn."
  (->handler [this] "Converts this to a handler"))

(extend-protocol ToHandler
  clojure.lang.Fn
  (->handler [f]
    (fn [evt]
      {:handler f
       :event evt
       :result (f evt)}))
  
  clojure.lang.PersistentArrayMap
  (->handler [m]
    (-> (:interceptors m)
        (concat [(i/handler-interceptor (:handler m))])
        (i/interceptor-handler))))

(defprotocol RouteMatcher
  (matches? [this evt]
    "Checks if this matches given event"))

(defn type-matcher
  "Handler matcher that assumes routes is a map and that matches by event type"
  [routes evt]
  (get routes (:type evt)))

(extend-type clojure.lang.Keyword
  RouteMatcher
  (matches? [k evt]
    (= k (:type evt))))

(defn route-matcher
  "Handler matcher that assumes the first item in each entry is a `RouteMatcher` and
   returns all handlers that match according to the protocol.  This allows for more
   flexibility compared to the default `type-matcher`.  Useful if for example you want
   to add a handler that can process multiple types of events without having to specify
   it at multiple places."
  [routes evt]
  (->> routes
       (filter (fn [[k h]]
                 (matches? k evt)))
       (map second)))

(defn sync-invoker
  "Handler invoker that invokes each of the handlers in sequence."
  [handlers evt]
  (mapv (fn [h] (h evt))
        handlers))

(defn compile-routes
  "Prepares routes so they can be used by the router by converting the handler values
   to actual handlers."
  [routes]
  (->> routes
       (reduce (fn [r [k v]]
                 (conj r [k (map ->handler v)]))
               [])
       ;; TODO This actually depends on the matcher
       (into {})))

(defn router
  "Creates an event router function.  It can be registered as a listener and it will
   route events according to the route configuration.  The router returns a structure
   holding the processed event, the handler, and the handler return value for each
   of the matched handlers.  If no handlers are found, returns `nil`.

   An extra options map can be passed in to override default behaviour:
     - `:matcher`: function that determines the handlers to invoke for an event, defaults to `type-matcher`.
     - `:invoker`: function that performs handler invocation.  By default this is the `sync-invoker`."
  [routes & [{:keys [matcher invoker]
              :or {matcher type-matcher
                   invoker sync-invoker}}]]
  ;; TODO If the matchers is a 2-arity function, we could invoke it first to get compiled routes
  (let [compiled (compile-routes routes)]
    (fn [evt]
      (when-let [h (matcher compiled evt)]
        (invoker h evt)))))
