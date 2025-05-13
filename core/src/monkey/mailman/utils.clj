(ns monkey.mailman.utils
  "Generic utility functions, usable by implementations"
  (:require [clojure.tools.logging :as log]
            [monkey.mailman.core :as c]))

(defn invoke-and-repost
  "Passes the event to each of the listeners and reposts the results to the broker."
  [evt broker listeners]
  (doseq [l listeners]
    (try
      (log/trace "Invoking listener:" l)
      (let [r (->> (c/invoke-listener l evt)
                   (map :result)
                   (flatten)
                   (remove nil?))]
        (log/trace "Posting result:" r)
        (c/post-events broker r))
      (catch Exception ex
        (log/error "Failed to handle event" ex)))))
