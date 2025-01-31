(ns monkey.mailman.interceptors
  "Event handler interceptors.  This uses the Pedestal interceptor library
   under the hood.  Most functions are really just wrappers around Pedestal
   functions."
  (:require [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as ic]
            [monkey.mailman.spec :as s]))

(def empty-context {})

(defn make-context
  "Creates an initial interceptor context for given event."
  [evt]
  {:event evt})

(def execute
  "Executes the interceptor chain with given context as argument."
  ic/execute)

(def interceptor
  "Converts its argument in an interceptor"
  i/interceptor)

(defn add-interceptors
  "Adds the given interceptors to the context"
  [ctx interceptors]
  (ic/enqueue ctx (map interceptor interceptors)))

(defn set-event
  "Sets the event on the context"
  [ctx evt]
  (assoc ctx :event evt))

(defn handler-interceptor
  "Interceptor that invokes the handler with the `:event` in the context.  Updates
   the context so it matches the `handler-result` spec."
  [handler]
  {:name ::handler
   :leave (fn [ctx]
            (assoc ctx
                   :result (handler (:event ctx))
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
   chain.  Executes the chain with the event set in the context."
  [interceptors]
  (let [ctx (-> empty-context
                (add-interceptors interceptors))]
    (fn [evt]
      (-> ctx
          (set-event evt)
          (execute)))))

