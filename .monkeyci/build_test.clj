(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [build :as sut]
            [monkey.ci
             [api :as m]
             [test :as mt]]
            [monkey.ci.protocols :as p]))

(deftest build-lib
  (let [ctx mt/test-ctx
        jobs ((sut/build-lib "core") ctx)]
    (testing "creates test job"
      (is (= 1 (count jobs)))
      (is (= "core-test" (m/job-id (first jobs)))))

    (testing "uses dir as working dir"
      (is (= "core" (m/work-dir (first jobs))))))

  (testing "on main branch, creates test and publish jobs"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/main"))
          jobs ((sut/build-lib "core") ctx)]
      (is (= 2 (count jobs)))))

  (testing "`manifold-test` is dependent on `core-publish` when core is published"
    (mt/with-build-params {}
      (let [jobs (p/resolve-jobs sut/jobs (-> mt/test-ctx
                                              (mt/with-git-ref "refs/heads/main")))
            manifold-test (->> jobs
                               (filter (comp (partial = "manifold-test") m/job-id))
                               (first))]
        (is (some? manifold-test))
        (is (contains? (set (m/dependencies manifold-test)) "core-publish"))))))

(deftest nats-lib
  (mt/with-build-params {"NATS_URL" "test-url"}
    (let [jobs (sut/nats-lib mt/test-ctx)]
     (testing "has test job"
       (is (= 1 (count jobs))))

     (testing "adds nats params to test job env"
       (is (= "test-url"
              (-> jobs
                  first
                  m/env
                  (get "NATS_URL"))))))))
