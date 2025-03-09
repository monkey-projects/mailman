(ns user
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.mailman
             [core :as c]
             [jms :as jms]]))

;;; Here follows an example of how an application that uses Mailman over JMS could look like.

(def builds "Fake database" (atom {}))

;;; Destination configuration

(def builds-topic "topic://mailman.builds::q")
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

(defn build-cons-evt
  "Creates consolidated event from the build"
  [build]
  {:type :build/updated
   :build-id (:id build)
   :build build})

;;; Interceptors

(def with-build
  "Looks up the build referred to in the event from database and adds it to the context.
   Not very useful, since the handler only receives the event, not the full context.  But
   the leave handler automatically updates the build, which should be in the context result."
  {:name ::with-build
   :enter (fn [ctx]
            (assoc ctx ::build (get @builds (get-in ctx [:events :build-id]))))
   :leave (fn [ctx]
            (update ctx :result
                    (fn [upd]
                      (-> (swap! builds update (:id upd) mc/deep-merge upd)
                          (get (:id upd))))))})

(def ->events
  "Converts the build in the result to a `build/updated` event"
  {:name ::to-events
   :leave (fn [{build :result :as ctx}]
            (update ctx :result (comp vector build-cons-evt)))})

;;; Handlers

(defn handle-build-evt [ctx]
  ;; Trivial now that interceptors take over part of the functionality
  (get-in ctx [:event :build]))

(defn handle-job-evt [ctx]
  (let [{:keys [job-id job]} (:event ctx)]
    {:jobs {job-id job}}))

(defn print-build-state [{:keys [build]}]
  (log/info "Build status:" (:status build)))

;;; Routing

(defn- build-routes [conf]
  (reduce (fn [r [types handler]]
            (->> types
                 (map (fn [t] [t [handler]]))
                 (concat r)))
          []
          conf))

(def routes
  (build-routes
   {[:build/start :build/end]
    {:handler handle-build-evt
     :interceptors [->events
                    with-build]}
    [:job/start :job/end]
    {:handler handle-job-evt
     :interceptors [->events
                    with-build]}
    [:build/updated]
    {:handler print-build-state}}))

;;; Broker setup

(defn add-listeners [broker]
  (let [router (c/router routes)]
    ;; Handle each of the destinations with the same router
    (->> destinations
         (vals)
         (distinct)
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

;;; Global state functions

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
