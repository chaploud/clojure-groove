(ns chaploud.groove.cli-test
  (:require [chaploud.groove.cli :as cli]
            [chaploud.groove.engine.output]
            [chaploud.groove.io]
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

(deftest render-and-songs-run-end-to-end
  (let [out (str (java.io.File/createTempFile "techno" ".wav"))]
    (is (re-find #"\.wav" (with-out-str (cli/run ["render" "techno" "1" out]))))
    (is (< 100000 (.length (java.io.File. out)))))
  (is (= (count (chaploud.groove.io/bundled-songs))
         (count (re-seq #"BPM" (with-out-str (cli/run ["songs"])))))))

(deftest usage-mistakes-are-reported
  (is (thrown-with-msg? Exception #"Usage" (cli/run ["bogus"])))
  (is (thrown-with-msg? Exception #"Usage" (cli/run ["render"]))))
