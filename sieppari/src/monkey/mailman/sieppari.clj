(ns monkey.mailman.sieppari
  "Interceptor implementation for Mailman that uses Sieppari as backing."
  (:require [sieppari
             [core :as sc]
             [interceptor :as si]]
            [monkey.mailman.interceptors :as mmi]))

(def ->interceptor
  "Converts its argument to an interceptor"
  si/into-interceptor)

(defn execute [interceptors ctx]
  (sc/execute-context interceptors ctx))
