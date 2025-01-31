(ns build
  (:require [monkey.ci.build.v2 :as m]
            [monkey.ci.plugin
             [clj :as clj]
             [github :as gh]]))

(defn- library-config [dir]
  {:test-job-id (str dir "-test")
   :publish-job-id (str dir "-publish")
   :artifact-id (str dir "-test-junit")})

(defn build-lib [dir]
  (fn [ctx]
    (->> ((clj/deps-library (library-config dir)) ctx)
         ;; Set work dir on each of the jobs
         (map #(m/work-dir % dir)))))

[(build-lib "core")
 (build-lib "manifold")
 (gh/release-job {:dependencies ["core-publish" "manifold-publish"]})]
