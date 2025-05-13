(ns monkey.mailman.nats.state
  "Broker state management functionality"
  (:require [monkey.nats
             [core :as nc]
             [jetstream :as js]]))

(deftype State [state])

(defn make-state
  ([init]
   (->State (atom init)))
  ([]
   (make-state {})))

;;; Low-level functions

(defn get-path [state path]
  (get-in @(.state state) path))

(defn update-state [state f & args]
  ;; TODO Use agents because swap! may invoke f multiple times
  (apply swap! (.state state) f args))

(defn update-path [state path f & args]
  (apply swap! (.state state) update-in path f args))

(defn get-or-add [state path f & args]
  (or (get-path state path)
      (-> (update-state state assoc-in path (apply f args))
          (get-in path))))

;;; Higher-level functions

(defn get-consumer-ctx [state conn {:keys [stream consumer]}]
  (let [ctx-path [:consumer-ctx stream consumer]]
    (or (get-path state ctx-path)
        (-> (update-state
             state
             (fn [s]
               (let [js (or (:jetstream s)
                            (js/make-jetstream conn))
                     ctx (js/consumer-ctx js stream consumer)]
                 (-> s
                     (assoc :jetstream js)
                     (assoc-in ctx-path ctx)))))
            (get-in ctx-path)))))

(defn set-fetcher [state fetcher]
  (update-state state assoc :fetcher fetcher))

(defn get-fetcher [state conn conf]
  (let [ctx (get-consumer-ctx state conn conf)]
    (get-or-add state [:fetcher]
                #(js/fetch ctx (-> (select-keys conf [:deserializer])
                                   (merge (:poll-opts conf)))))))

(defn close-fetcher [state]
  (update-state
   state
   (fn [{:keys [fetcher] :as s}]
     (when fetcher
       (.close fetcher))
     (dissoc s :fetcher))))

;; (defn register-subscription [state id s]
;;   (update-state state assoc-in [:subscriptions id] s))

;; (defn unregister-subscription [state id]
;;   (when-let [s (get-path state [:subscriptions id])]
;;     (nc/unsubscribe s)
;;     (update-state state update :subscriptions dissoc id)
;;     true))

(defn- opts->sub-id [opts]
  (select-keys opts [:subject :queue :deserializer]))

(defn- sub-path [opts]
  [:subscriptions (opts->sub-id opts)])

(defn get-sub [state opts]
  (get-path state (sub-path opts)))

(defn- register-listener [state path listener make-sub]
  (if (:sub (get-path state path))
    (update-path state (conj path :listeners) assoc (:id listener) listener)
    (let [sub (make-sub (comp vals :listeners #(get-path state path)))]
      (update-state state assoc-in path {:listeners {(:id listener) listener}
                                         :sub sub})))
  listener)

(defn- unregister-listener [state path id unsubscriber]
  (letfn [(maybe-unsubscribe [{:keys [listeners sub] :as s}]
            (when (empty? listeners)
              (unsubscriber sub))
            s)]
    (if-let [match (->> (get-path state path)
                        (filter (fn [[_ v]]
                                  (contains? (:listeners v) id)))
                        (first))]
      (-> state
          (update-path (conj path (first match))
                       (fn [s]
                         (-> s
                             (update :listeners dissoc id)
                             (maybe-unsubscribe))))
          (some?))
      false)))

(defn subscribe
  "Either adds a listener to the existing subscription of the same options,
   or creates a new subscription.  The subscription maker gets passed in a
   function that returns the list of registered listeners."
  [state listener opts make-sub]
  (register-listener state (sub-path opts) listener make-sub))

(defn unsubscribe
  "Removes given listener from subscriptions.  If no more listeners remain, the 
   subscription is closed."
  [state id]
  (unregister-listener state [:subscriptions] id nc/unsubscribe))

(defn- opts->cons-id [opts]
  (select-keys opts [:stream :consumer]))

(defn- cons-path [opts]
  [:consumers (opts->sub-id opts)])

(defn get-cons [state opts]
  (get-path state (cons-path opts)))

(defn register-consumer [state listener opts maker]
  (register-listener state (cons-path opts) listener maker))

(defn unregister-consumer [state id]
  (unregister-listener state [:consumers] id (fn [c]
                                               (.stop c)
                                               (.close c))))

