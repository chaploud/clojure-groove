(ns chaploud.groove.render-test
  (:require [chaploud.groove.engine.mixer :as mixer]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.live :as live]
            [chaploud.groove.session :as s]
            [clojure.test :refer [deftest is]]))

(defn- render [& tracks]
  (let [sess (reduce (fn [acc [k node]] (s/play acc k node)) s/empty-session (partition 2 tracks))]
    (live/render sess 1)))

(defn- rms [^bytes pcm from to]
  (let [bb (.order (java.nio.ByteBuffer/wrap pcm) java.nio.ByteOrder/LITTLE_ENDIAN)
        n (- to from)]
    (Math/sqrt (/ (reduce + (for [i (range from to)
                                  :let [x (double (.getShort bb (int (* 4 i))))]]
                              (* x x)))
                  n))))

(deftest kicks-land-on-the-beat
  (let [pcm (render :k [:steps {:inst :drum/kick} "x... x... x... x..."])
        beat (/ 48000 2)]
    (is (> (rms pcm 0 2000) 3000))
    (is (> (rms pcm beat (+ beat 2000)) (* 2 (rms pcm (- beat 2000) beat))))))

(deftest rendering-is-deterministic
  (let [tracks [:h [:steps {:inst :drum/hat :prob 0.5} "xxxx xxxx xxxx xxxx"]
                :b [:notes {:inst :synth/supersaw} [:q 0 2 4 7]]]]
    (is (java.util.Arrays/equals ^bytes (apply render tracks) ^bytes (apply render tracks)))
    (is (> (rms (apply render tracks) 0 48000) 500))))

(deftest a-non-finite-voice-does-not-silence-the-mix
  (let [mx (mixer/make-mixer 48000)
        kick (instruments/resolve-instrument {} :drum/kick)
        broken (assoc (instruments/resolve-instrument {} :synth/acid) :fdecay 0.0 :midi 45 :vel 0.8 :dur-s 0.2)
        ^doubles l (:out-l mx)]
    (mixer/submit! mx 0 broken 1)
    (mixer/submit! mx 48000 (assoc kick :vel 0.9 :dur-s 0.1) 2)
    (let [peak (volatile! 0.0)
          non-finite (volatile! 0)]
      (dotimes [b 300]
        ((:render! mx))
        (dotimes [i (alength l)]
          (when-not (Double/isFinite (aget l i)) (vswap! non-finite inc))
          (when (> b 187) (vswap! peak max (Math/abs (aget l i))))))
      (is (zero? @non-finite))
      (is (> @peak 0.1) "the kick after the broken voice is audible"))))

(deftest downbeats-are-sample-accurate-offline
  (let [sess (-> (s/play s/empty-session :k [:steps {:inst :drum/kick :click 0.0} "x... .... .... ...."])
                 (assoc-in [:globals :tempo] 137))
        pcm (live/render sess 3)
        bb (.order (java.nio.ByteBuffer/wrap pcm) java.nio.ByteOrder/LITTLE_ENDIAN)
        bar-frames (/ (* 4 60.0 48000) 137)]
    (doseq [bar [1 2]
            :let [expected (Math/round (* bar bar-frames))
                  onset (first (filter #(not (zero? (.getShort bb (int (* 4 %)))))
                                       (range (- expected 300) (+ expected 300))))]]
      (is (<= (Math/abs (- onset expected)) 1) (str "bar " bar)))))

(deftest a-closed-hat-chokes-the-open-hat
  (let [tail (fn [tracks]
               (let [pcm (apply render tracks)]
                 (rms pcm 6000 20000)))]
    (is (< (* 3 (tail [:o [:steps {:inst :drum/open-hat} "x..............."]
                       :c [:steps {:inst :drum/hat :vel 0.01} ".x.............."]]))
           (tail [:o [:steps {:inst :drum/open-hat} "x..............."]])))))

(deftest a-glide-bends-the-oscillators
  (let [plain (render :a [:notes {:inst :synth/acid} [:q 0 4 0 4]])
        slid (render :a [:notes {:inst :synth/acid} [:q 0 {:degree 4 :glide true :glide-time 0.3} 0 4]])]
    (is (not (java.util.Arrays/equals ^bytes plain ^bytes slid)))))

(deftest non-finite-output-is-counted-for-reporting
  (let [mx (mixer/make-mixer 48000)
        broken (assoc (instruments/resolve-instrument {} :synth/acid) :fdecay 0.0 :midi 45 :vel 0.8 :dur-s 0.2)]
    (mixer/submit! mx 0 broken 1)
    (dotimes [_ 20] ((:render! mx)))
    (is (pos? ((:take-non-finite-resets! mx))))
    (is (zero? ((:take-non-finite-resets! mx))))))

(deftest a-highpass-thins-a-low-note
  (let [low #(rms (render :b [:notes (merge {:inst :synth/sub :octave 2} %) [:w 0]]) 4000 40000)]
    (is (< (* 4 (low {:hp 1000.0})) (low {})))))

(deftest delay-feedback-keeps-the-echo-repeating
  (let [tail (fn [fb]
               (let [sess (-> s/empty-session
                              (assoc-in [:globals :delay-feedback] fb)
                              (s/play :p [:notes {:inst :synth/skank :delay 1.0} [:w 0]]))]
                 (rms (live/render sess 1) 72000 96000)))]
    (is (< (* 4 (tail 0.0)) (tail 0.9)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (s/validate! (assoc-in s/empty-session [:globals :delay-feedback] 1.2))))))

(deftest duck-depth-sets-how-far-the-kick-pushes-the-synths-down
  (let [level (fn [depth]
                (let [sess (-> s/empty-session
                               (assoc-in [:globals :duck-depth] depth)
                               (s/play :k [:steps {:inst :drum/kick :gain 0.0} "x..."])
                               (s/play :p [:notes {:inst :synth/pad :attack 0.001} [:w 0]]))]
                  (rms (live/render sess 1) 1000 3000)))]
    (is (< (* 2 (level 0.75)) (level 0.0)))))
