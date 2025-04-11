(ns build
  (:require [monkey.ci.build
             [api :as api]
             [v2 :as m]]
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

(defn- find-test-job [jobs]
  (->> jobs
       (filter test-job?)
       (first)))

(defn dependent-lib
  "Library that's dependent on the core.  Creates the usual test and publish jobs,
   but makes the test job dependent on `core-publish` if core has been published."
  [dir]
  (fn [ctx]
    (let [jobs ((build-lib dir) ctx)
          test-job (find-test-job jobs)]
      (cond->> (vec jobs)
        (core-published? ctx) (replace {test-job (m/depends-on test-job (publish-id "core"))})))))

(defn nats-lib
  "Creates jobs for building the nats library.  We can't use the default fn above because
   it needs env vars for testing."
  [ctx]
  (let [params (-> (api/build-params ctx)
                   (select-keys ["NATS_URL" "NATS_CREDS"]))
        jobs (vec ((dependent-lib "nats") ctx))
        test-job (find-test-job jobs)]
    (replace {test-job (m/env test-job params)} jobs)))

(def dep-libs ["manifold" "jms"])
(def libs (concat ["core" "nats"] dep-libs))

;; Put jobs in var so we can get them for testing
(def jobs
  [(build-lib "core")
   (map dependent-lib dep-libs)
   nats-lib
   (gh/release-job {:dependencies (map publish-id libs)})])
