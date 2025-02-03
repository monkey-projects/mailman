(ns user
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.mailman
             [core :as c]
             [jms :as jms]]))

(def builds-topic "topic://mailman.builds")
(def jobs-topic "topic://mailman.jobs")
(def builds-cons-topic "topic://mailman.builds.consolidated")

(def destinations
  {:build/start builds-topic
   :build/end builds-topic
   :build/updated builds-cons-topic
   :job/start jobs-topic
   :job/end jobs-topic})

(def destination-mapper
  "Maps an event to a destination"
  (comp destinations :type))

(def builds (atom {}))

(defn build-cons-evt
  "Creates consolidated event from the build"
  [build]
  {:type :build/updated
   :build-id (:id build)
   :build build})

(defn ->events [state build-id]
  (-> state
      (get build-id)
      (build-cons-evt)
      (vector)))

(defn handle-build-evt [{:keys [build-id build]}]
  (-> (swap! builds update build-id merge build)
      (->events build-id)))

(defn handle-job-evt [{:keys [build-id job-id job]}]
  (-> (swap! builds update-in [build-id :jobs job-id] merge job)
      (->events build-id)))

(defn print-build-state [{:keys [build]}]
  (log/info "Build state:" (:state build)))

(def routes
  [[:build/start [handle-build-evt]]
   [:build/end [handle-build-evt]]
   [:job/start [handle-job-evt]]
   [:job/end [handle-job-evt]]
   [:build/updated [print-build-state]]])

(defn add-listeners [broker]
  (let [router (c/router routes)]
    (->> destinations
         (vals)
         (map (fn [d]
                (c/add-listener broker {:destination d
                                        :handler router})))
         (doall))
    broker))

(defn connect
  "Connects to a JMS broker using given config"
  [config]
  (-> (jms/jms-broker (assoc config
                             :destination-mapper destination-mapper))
      (add-listeners)))

(defn post [broker evt]
  (c/post-events broker [evt]))

(defonce broker (atom nil))

(defn load-config []
  (with-open [r (java.io.PushbackReader. (io/reader (io/resource "config.edn")))]
    (edn/read r)))

(defn connect! []
  (swap! broker (fn [old]
                  (when old
                    (jms/disconnect old))
                  (connect (load-config))))
  nil)

(defn disconnect! []
  (swap! broker jms/disconnect))

(defn post! [evt]
  (post @broker evt))
