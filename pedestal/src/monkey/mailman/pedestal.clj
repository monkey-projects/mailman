(ns monkey.mailman.pedestal
  "Interceptor implementation for Mailman that uses Pedestal as backing"
  (:require [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as ic]
            [monkey.mailman.interceptors :as mmi]))

(def ->interceptor
  "Converts its argument to an interceptor"
  i/interceptor)

(defn execute
  "Interceptor executor that can be passed as an `:executor` to the router"
  [interceptors ctx]
  (-> ctx
      (ic/enqueue (map ->interceptor interceptors))
      (ic/execute)))
