(ns monkey.mailman.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.mailman
             [core :as sut]
             [spec :as spec]]))

(deftest sync-invoker
  (testing "invokes all handlers sequentially"
    (is (= [::first ::second] (sut/sync-invoker [(constantly ::first)
                                                 (constantly ::second)]
                                                ::test-event)))))

(deftest ->handler
  (testing "converts function to handler"
    (let [h (sut/->handler (fn [evt]
                             {:handled evt}))]
      (is (fn? (:handler h)))))

  (testing "converts map to handler"
    (let [h (sut/->handler {:handler (constantly ::handled)})]
      (is (fn? (:handler h))))))

(deftest handler->fn
  (let [test-interceptor {:name ::test
                          :enter #(assoc % :intercepted? true)}
        h (sut/handler->fn
           (sut/->handler {:handler (constantly ::handled)
                           :interceptors [test-interceptor]}))]
    (testing "returns fn"
      (is (fn? h)))

    (testing "applies interceptors"
      (is (= {:result ::handled
              :intercepted? true}
             (-> (h ::test-event)
                 (select-keys [:result :intercepted?])))))))

(deftest router
  (let [handled (atom [])
        handler (fn [{:keys [event]}]
                  (swap! handled conj event))
        r (sut/router {::test-type [handler]})]
    (testing "is invokable"
      (is (ifn? r)))

    (testing "dispatches event according to type"
      (let [evt {:type ::test-type}]
        (is (spec/results? (r evt))
            "returns router result structure")
        (is (= [evt] @handled))))

    (testing "`nil` when no handlers found"
      (is (nil? (r {:type ::other-type})))))

  (testing "can override route matcher"
    (let [r (sut/router {::test-type [(constantly ::ok)]}
                        {:matcher (constantly false)})]
      (is (nil? (r {:type ::test-type})))))

  (testing "invokes multiple handlers in sequence"
    (let [r (sut/router {::test-type [(constantly ::first) (constantly ::second)]})]
      (is (= [::first ::second]
             (->> (r {:type ::test-type})
                  (map :result))))))

  (testing "can override invocation"
    (let [r (sut/router {::test-type [(constantly ::result)]}
                        {:invoker (constantly ::overridden)})]
      (is (= ::overridden (r {:type ::test-type})))))

  (testing "applies global interceptors"
    (let [test-interceptor {:name ::test
                            :enter (fn [ctx]
                                     (assoc ctx :intercepted? true))}
          r (sut/router {::test-type [(constantly ::result)]}
                        {:interceptors [test-interceptor]})]
      (is (= {:result ::result
              :intercepted? true}
             (-> (r {:type ::test-type})
                 (first)
                 (select-keys [:result :intercepted?])))))))

(deftest replace-interceptors
  (let [test-interceptor {:name ::test
                          :enter (fn [ctx]
                                   (assoc ctx ::interceptor ::orig))}
        new-interceptor {:name ::test
                         :enter (fn [ctx]
                                  (assoc ctx ::interceptor ::new))}
        router (sut/router {::test-event [{:handler ::interceptor
                                           :interceptors [test-interceptor]}]})
        rep (sut/replace-interceptors router [new-interceptor])]
    (testing "can override interceptors on router"
      (is (= ::orig
             (-> (router {:type ::test-event})
                 first
                 :result)))

      (is (= ::new
             (-> (rep {:type ::test-event})
                 first
                 :result))))))
