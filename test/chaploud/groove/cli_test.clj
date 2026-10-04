(ns chaploud.groove.cli-test
  (:require [chaploud.groove.cli :as cli]
            [clojure.test :refer [deftest is]]))

(deftest options-may-appear-anywhere
  (is (= [{:bars 4 :device "USB"} ["trance"]]
         (#'cli/options ["--bars" "4" "trance" "--device" "USB"]))))
