(ns monkey.mailman.spec
  (:require [clojure.spec.alpha :as s]))

(s/def ::type keyword?)

(s/def ::event
  (s/keys :req-un [::type]))

(s/def ::events
  (s/coll-of ::event))

(s/def ::handler fn?)

(s/def ::router-result
  (s/keys :req-un [::event ::handler ::result]))

(def event?
  "Checks if the argument is a valid event"
  (partial s/valid? ::event))

(def events?
  "Checks if the argument is a valid list of events"
  (partial s/valid? ::events))
