(ns monkey.mailman.interceptors
  "Event handler interceptors.  This uses the Pedestal interceptor library
   under the hood.  Most functions are really just wrappers around Pedestal
   functions."
  (:require [monkey.mailman.spec :as s]))

(def empty-context {})

(defn make-context
  "Creates an initial interceptor context for given event."
  [evt]
  (assoc empty-context :event evt))

(defn set-event
  "Sets the event on the context"
  [ctx evt]
  (assoc ctx :event evt))

(defn handler-interceptor
  "Interceptor that invokes the handler with the context.  Updates the context
   with the handler return value so it matches the `handler-result` spec."
  [handler]
  {:name ::handler
   :leave (fn [ctx]
            (assoc ctx
                   :result (handler ctx)
                   :handler handler))})

(defn- sanitize [x valid?]
  (cond
    (sequential? x) (->> (mapcat #(sanitize % valid?) x)
                         (remove nil?))
    (valid? x) [x]
    :else nil))

(defn sanitize-result [& {:keys [event?]
                          :or {event? s/event?}}]
  "Cleans up result so it only contains valid events"
  {:name ::sanitize-result
   :leave (fn [ctx]
            (update ctx :result sanitize event?))})

(defn interceptor-handler
  "Creates an event handler fn that uses the given interceptors as the interceptor
   chain and passes them to the executor, along with the context, that has the event
   added."
  [interceptors exec]
  (fn [evt]
    (exec interceptors
          (set-event empty-context evt))))

