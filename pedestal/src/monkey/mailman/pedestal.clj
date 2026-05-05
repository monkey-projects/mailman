(ns monkey.mailman.pedestal
  "Interceptor implementation for Mailman that uses Pedestal as backing"
  (:require [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as ic]
            [monkey.mailman.interceptors :as mmi]))

(def ->interceptor
  "Converts its argument to an interceptor"
  i/interceptor)

(defn add-interceptors
  "Adds the given interceptors to the context"
  [ctx interceptors]
  (ic/enqueue ctx (map ->interceptor interceptors)))

(defrecord PedestalInterceptorChain [interceptors]
  mmi/InterceptorChain
  (execute [this ctx]
    (-> ctx
        (add-interceptors interceptors)
        (ic/execute))))

(def interceptor-chain ->PedestalInterceptorChain)
