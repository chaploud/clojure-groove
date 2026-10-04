(ns chaploud.groove.cli-test
  (:require [chaploud.groove.cli :as cli]
            [chaploud.groove.engine.output]
            [clojure.test :refer [deftest is]]))

(deftest options-may-appear-anywhere
  (is (= [{:bars 4 :device "USB"} ["trance"]]
         (#'cli/options ["--bars" "4" "trance" "--device" "USB"]))))

(deftest bad-bars-are-reported-not-ignored
  (is (thrown-with-msg? Exception #"bars must be a whole number" (#'cli/options ["--bars"])))
  (is (thrown-with-msg? Exception #"bars must be a whole number" (#'cli/options ["--bars" "abc"]))))

(deftest devices-match-exact-names-first
  (is (= "MacBook Speakers" (chaploud.groove.engine.output/pick-device ["USB Speakers" "MacBook Speakers"] "macbook speakers")))
  (is (= "USB Speakers" (chaploud.groove.engine.output/pick-device ["USB Speakers" "MacBook Speakers"] "usb")))
  (is (nil? (chaploud.groove.engine.output/pick-device ["USB Speakers"] "hdmi"))))
