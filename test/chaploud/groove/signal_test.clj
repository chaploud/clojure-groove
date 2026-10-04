(ns chaploud.groove.signal-test
  (:require [chaploud.groove.session :as s]
            [chaploud.groove.signal :as sig]
            [clojure.test :refer [deftest is testing]]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest lfo-shapes
  (is (close? 100 (sig/value [:lfo :sine 2 100 300] 0)))
  (is (close? 300 (sig/value [:lfo :sine 2 100 300] 1)))
  (is (close? 200 (sig/value [:lfo :tri 4 100 300] 1)))
  (is (close? 150 (sig/value [:lfo :saw 4 100 300] 1)))
  (is (close? 300 (sig/value [:lfo :square 2 100 300] 1.5)))
  (is (thrown-with-msg? Exception #"LFO shape" (sig/value [:lfo :wobble 2 0 1] 0))))

(deftest ramps-repeat-with-their-length
  (is (close? 300 (sig/value [:ramp 300 4300 8] 0)))
  (is (close? 2300 (sig/value [:ramp 300 4300 8] 4)))
  (is (close? 300 (sig/value [:ramp 300 4300 8] 8))))

(deftest events-take-the-signal-value-at-their-onset
  (let [sess (-> (s/play s/empty-session :p [:notes {:inst :synth/pluck :cutoff [:ramp 400 2000 2]} [:q 0 0 0 0]])
                 (s/begin-bar 0))
        compiled (s/validate! sess)
        cutoffs (map :cutoff (concat (s/bar-events sess compiled 0) (s/bar-events sess compiled 1)))]
    (is (= [400.0 600.0 800.0 1000.0 1200.0 1400.0 1600.0 1800.0] cutoffs)))
  (is (thrown-with-msg? Exception #"positive length"
                        (s/validate! (s/play s/empty-session :p [:notes {:inst :synth/pluck :cutoff [:ramp 1 2 0]} [0]])))))

(deftest signals-work-on-timing-pitch-and-nested-attributes
  (let [events (fn [node]
                 (let [sess (s/begin-bar (s/play s/empty-session :t node) 0)]
                   (s/bar-events sess (s/validate! sess) 0)))]
    (is (= 4 (count (events [:steps {:inst :drum/hat :prob [:ramp 1.0 1.0 4]} "x... x... x... x..."]))))
    (is (every? integer? (map :midi (events [:notes {:inst :synth/acid :transpose [:ramp 0 12 4]} [:q 0 2 4 5]]))))
    (is (= 3 (count (events [:notes {:inst :synth/pad} [:w #{0 {:degree 2 :transpose [:ramp 0 12 4]} 4}]]))))
    (testing "a nudged note takes the value of where it is written"
      (is (= [300.0] (map :cutoff (events [:notes {:inst :synth/pluck :nudge -0.5 :cutoff [:ramp 300.0 6000.0 8]}
                                           [:w 0]])))))))
