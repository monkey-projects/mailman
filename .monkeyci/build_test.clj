(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [build :as sut]
            [monkey.ci.test :as mt]
            [monkey.ci.build.v2 :as m]))

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
      (is (= 2 (count jobs))))))
