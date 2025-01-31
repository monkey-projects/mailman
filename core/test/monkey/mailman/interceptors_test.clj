(ns monkey.mailman.interceptors-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.mailman.interceptors :as sut]))

(deftest handler-interceptor
  (testing "invokes handler with event, adds result to context"
    (let [handler (fn [evt]
                    {::test-result evt})
          evt {:type ::test-event}
          res (-> (sut/make-context evt)
                  (sut/add-interceptors [(sut/handler-interceptor handler)])
                  (sut/execute))]
      (is (= evt (:event res)))
      (is (= {::test-result evt} (:result res))))))

(deftest interceptor-handler
  (let [test-interceptor {:enter #(assoc % ::called? true)}
        h (sut/interceptor-handler [test-interceptor
                                    (sut/handler-interceptor (constantly ::handled))])]
    (testing "returns a fn"
      (is (fn? h)))

    (testing "invokes interceptors"
      (let [r (h {:type ::test-evt})]
        (is (= ::handled (:result r)))
        (is (true? (::called? r)))))))

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

