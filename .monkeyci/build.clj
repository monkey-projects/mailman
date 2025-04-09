(ns build
  (:require [monkey.ci.build.v2 :as m]
            [monkey.ci.plugin
             [clj :as clj]
             [github :as gh]]))

(def base-conf {})
(def core-changed? (m/touched? "core/.*"))

(def core-published? (partial clj/should-publish? base-conf))

(defn- test-id [dir]
  (str dir "-test"))

(defn- publish-id [dir]
  (str dir "-publish"))

(defn- library-config [dir]
  (assoc base-conf
         :test-job-id (test-id dir)
         :publish-job-id (publish-id dir)
         :artifact-id (str dir "-test-junit")))

(defn jobs-maker [dir]
  (clj/deps-library (library-config dir)))

(defn build-lib [dir]
  (fn [ctx]
    (let [maker (jobs-maker dir)]
      (->> (maker ctx)
           ;; Set work dir on each of the jobs
           (map #(m/work-dir % dir))))))

(defn- test-job? [job]
  (some->> job
           (m/job-id)
           (re-matches #"^.*-test$")))

(defn dependent-lib
  "Library that's dependent on the core.  Creates the usual test and publish jobs,
   but makes the test job dependent on `core-publish` if core has been published."
  [dir]
  (fn [ctx]
    (let [jobs ((build-lib dir) ctx)
          test-job (->> jobs
                        (filter test-job?)
                        (first))]
      (cond->> (vec jobs)
        (core-published? ctx) (replace {test-job (m/depends-on test-job (publish-id "core"))})))))

(def dep-libs ["manifold" "jms" "nats"])
(def libs (concat ["core"] dep-libs))

;; Put jobs in var so we can get them for testing
(def jobs
  [(build-lib "core")
   (map dependent-lib dep-libs)
   (gh/release-job {:dependencies (map publish-id libs)})])
