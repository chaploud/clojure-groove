(ns chaploud.groove.signal-test
  (:require [chaploud.groove.session :as s]
            [chaploud.groove.signal :as sig]
            [clojure.test :refer [deftest is]]))

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
