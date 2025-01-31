(ns build
  (:require [monkey.ci.build.v2 :as m]
            [monkey.ci.plugin
             [clj :as clj]
             [github :as gh]]))

(defn build-lib [dir]
  (fn [ctx]
    (->> ((clj/deps-library
           {:test-job-id (str dir "-test")
            :publish-job-id (str dir "-publish")}) ctx)
         (map #(m/work-dir % dir)))))

[(build-lib "core")
 (build-lib "manifold")
 (gh/release-job {:dependencies ["core-publish" "manifold-publish"]})]
