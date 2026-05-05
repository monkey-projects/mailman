(ns monkey.mailman.interceptors-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.mailman.interceptors :as sut]))

(defrecord FakeChain [interceptors]
  sut/InterceptorChain
  (execute [this ctx]
    {:interceptors (:interceptors this)
     :ctx ctx}))

(deftest handler-interceptor
  (testing "`leave` invokes handler with context, adds result to context"
    (let [handler (fn [{:keys [event]}]
                    {::test-result event})
          evt {:type ::test-event}
          {:keys [leave] :as i} (sut/handler-interceptor handler)
          res (leave {:event evt})]
      (is (= evt (:event res)))
      (is (= {::test-result evt} (:result res))))))

(deftest interceptor-handler
  (let [test-interceptor {:enter #(assoc % ::called? true)}
        h (sut/interceptor-handler (->FakeChain [test-interceptor]))]
    (testing "returns a fn"
      (is (fn? h)))

    (testing "invokes interceptors"
      (let [evt {:type ::test-evt}
            r (h evt)]
        (is (= [test-interceptor] (:interceptors r)))
        (is (= evt (get-in r [:ctx :event])))))))

(deftest sanitize-result
  (let [i (sut/sanitize-result)
        f (:leave i)
        evt {:type ::valid}]
    
    (testing "provides `leave` handler"
      (is (fn? f)))
    
    (testing "wraps events in vector"
      (is (= [evt] (-> {:result evt}
                       (f)
                       :result))))

    (testing "leaves collections as-is"
      (is (= [evt] (-> {:result [evt]}
                       (f)
                       :result))))

    (testing "removes non-events"
      (is (empty? (-> {:result [:invalid]}
                      (f)
                      :result)))

      (is (empty? (-> {:result :invalid}
                      (f)
                      :result)))

      (is (= [evt] (-> {:result [:invalid evt]}
                       (f)
                       :result))))))

