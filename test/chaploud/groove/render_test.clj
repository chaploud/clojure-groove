(ns chaploud.groove.render-test
  (:require [chaploud.groove.live :as live]
            [chaploud.groove.session :as s]
            [clojure.test :refer [deftest is]]))

(defn- render [& tracks]
  (let [sess (reduce (fn [acc [k node]] (s/play acc k node)) s/empty-session (partition 2 tracks))]
    (live/render {:session sess :compiled (s/validate! sess)} 1)))

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
    (is (java.util.Arrays/equals ^bytes (apply render tracks) ^bytes (apply render tracks)))))
