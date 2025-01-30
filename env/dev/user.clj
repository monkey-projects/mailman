(ns user
  (:require [clojure.tools.logging :as log]
            [monkey.mailman
             [core :as c]
             [interceptors :as i]
             [mem :as mem]
             [spec :as s]]))

(defonce broker (atom nil))
(defonce listener (atom nil))

(defn event [type body]
  (assoc body :type type))

(defn initializer [evt]
  (log/info "Initializing")
  (event ::start {:message "System started"}))

(def logger
  {:name ::event-logger
   :enter (fn [{:keys [event] :as ctx}]
            (log/debug "Handling:" event)
            ctx)
   :leave (fn [{:keys [event result] :as ctx}]
            (log/debug "Handled:" event)
            (when (not-empty result)
              (log/debug "Result:" result))
            ctx)})

(def add-time
  "Adds timestamp to each event"
  {:name ::add-timestamp
   :enter (fn [ctx]
            (assoc-in ctx [:event :ts] (System/currentTimeMillis)))})

(def interceptors
  [add-time
   logger
   (i/sanitize-result)])

(def routes
  {::init [{:handler initializer
            :interceptors interceptors}]})

(def router (c/router routes))

;;; Starting and stopping the broker

(declare stop!)

(defn start! []
  (stop!)
  (reset! broker (mem/make-memory-events))
  (reset! listener (c/add-listener @broker router)))

(defn stop! []
  (swap! broker
         (fn [b]
           (when b
             (c/unregister-listener @listener)
             nil))))

(defn post! [evt]
  (when @broker
    (c/post-events @broker [evt])))
