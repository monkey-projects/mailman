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

(defrecord Handler [handler interceptors])

(extend-protocol ToHandler
  clojure.lang.Fn
  (->handler [f]
    (->Handler f nil))
  
  clojure.lang.PersistentArrayMap
  (->handler [m]
    (map->Handler m)))

(defn handler->fn
  "Converts the handler into an invokable function that applies interceptors"
  [handler]
  (-> (:interceptors handler)
      (concat [(i/handler-interceptor (:handler handler))])
      (i/interceptor-handler)))

(defprotocol RouteMatcher
  "Used by the router to find handlers for an incoming event."
  (compile-routes [this routes]
    "Compiles routes in a format that is more suitable for the matcher")
  (find-handlers [this routes evt]
    "Checks if this matches given event"))

(defrecord TypeMatcher []
  RouteMatcher
  (compile-routes [_ routes]
    (into {} routes))
  
  (find-handlers [_ routes evt]
    (get routes (:type evt))))

(extend-type clojure.lang.Fn
  RouteMatcher
  (compile-routes [_ routes]
    routes)

  (find-handlers [this routes evt]
    (this routes evt)))

(def type-matcher
  "Handler matcher that assumes routes is a map and that matches by event type"
  (->TypeMatcher))

(defn sync-invoker
  "Handler invoker that invokes each of the handlers in sequence."
  [handlers evt]
  (mapv (fn [h] (h evt)) handlers))

(defn- convert-handlers [routes]
  (->> routes
       (reduce (fn [r [k v]]
                 (conj r [k (map ->handler v)]))
               [])
       (doall)))

(defn- add-global-interceptors [interceptors routes]
  (letfn [(convert [h]
            (cond-> h
              (not-empty interceptors)
              (update :interceptors (comp vec (partial concat interceptors)))
              true
              (handler->fn)))]
    (mapv (fn [[k handlers]]
            [k (map convert handlers)])
          routes)))

(defn router
  "Creates an event router function.  It can be registered as a listener and it will
   route events according to the route configuration.  The router returns a structure
   holding the processed event, the handler, and the handler return value for each
   of the matched handlers.  If no handlers are found, returns `nil`.

   An extra options map can be passed in to override default behaviour:
     - `:matcher`: function that determines the handlers to invoke for an event, defaults to `type-matcher`.
     - `:invoker`: function that performs handler invocation.  By default this is the `sync-invoker`.
     - `:interceptors`: custom interceptors, prepended to the route-specific interceptors."
  [routes & [{:keys [matcher invoker interceptors]
              :or {matcher type-matcher
                   invoker sync-invoker}}]]
  (let [compiled (->> routes
                      (convert-handlers)
                      (add-global-interceptors interceptors)
                      (compile-routes matcher))]
    (fn [evt]
      (when-let [h (find-handlers matcher compiled evt)]
        (invoker h evt)))))
