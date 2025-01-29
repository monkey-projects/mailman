(ns build
  (:require [monkey.ci.plugin
             [clj :as clj]
             [github :as gh]]))

[(clj/deps-library)
 (gh/release-job {:dependencies "publish"})]
