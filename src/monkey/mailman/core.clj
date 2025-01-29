(ns monkey.mailman.core
  (:require [monkey.mailman.spec :as spec]))

(defprotocol EventPoster
  (post-events [this events]
    "Posts one or more events"))

(defprotocol EventReceiver
  (pull-events [this n]
    "Returns up to `n` pending events, using pull model.  If `n` is `nil`, returns all
     pending events.")

  (add-listener [this listener]
    "Registers a listener to receive incoming events"))

(defprotocol Listener
  (invoke-listener [this evt]
    "Invokes this listener with the given event")
  (unregister-listener [this]
    "Unregisters the listener from the event receiver it was previously added to.  
     Returns `true` if it was succesfully unregistered."))

(defprotocol RouteMatcher
  (matches? [this evt]
    "Checks if this matches given event"))

(defn pull-next
  "Pulls next event from the receiver"
  [e]
  (some-> (pull-events e 1) first))

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
   flexibility compared to the default `type-matcher`."
  [routes evt]
  (->> routes
       (filter (fn [[k h]]
                 (matches? k evt)))
       (map second)))

(defn sync-invoker
  "Handler invoker that invokes each of the handlers in sequence."
  [handlers evt]
  (mapv (fn [h] {:handler h
                 :event evt
                 :result (h evt)})
        handlers))

(defn router
  "Creates an event router function.  It can be registered as a listener and it will
   route events according to the route configuration.  The router returns a structure
   holding the processed event, the handler, and the handler return value for each
   of the matched handlers.  If no handlers are found, returns `nil`."
  [routes & [{:keys [matcher invoker]
              :or {matcher type-matcher
                   invoker sync-invoker}}]]
  (fn [evt]
    (when-let [h (matcher routes evt)]
      (invoker h evt))))
