(ns chaploud.groove.session-test
  (:require [chaploud.groove.session :as s]
            [clojure.test :refer [deftest is testing]]))

(defn- session [& tracks]
  (reduce (fn [acc [k node]] (s/play acc k node)) s/empty-session (partition 2 tracks)))

(defn- events [sess bar]
  (s/bar-events sess (s/validate! sess) bar))

(deftest synth-parameters
  (let [[e] (events (-> (session :bass [:notes {:inst :synth/acid} [:q 0]])
                        (assoc :globals {:tempo 120 :root :a :scale :minor}))
                    0)]
    (testing "the instrument preset supplies defaults, including the octave"
      (is (= 45 (:midi e))))
    (testing "duration in seconds honours the preset's gate"
      (is (< (Math/abs (- (* 0.5 0.7) (:dur-s e))) 1e-9)))))

(deftest validation-happens-before-adoption
  (is (thrown-with-msg? Exception #"Unknown instrument :drum/nope"
                        (s/validate! (session :x [:steps {:inst :drum/nope} "x"]))))
  (is (thrown-with-msg? Exception #"has no pitch"
                        (s/validate! (session :x [:steps {:inst :synth/acid} "x"]))))
  (is (thrown-with-msg? Exception #"Track :x: Undefined reference"
                        (s/validate! (session :x :clip/missing))))
  (is (thrown-with-msg? Exception #"simple keywords" (s/play s/empty-session :a/b [:steps "x"])))
  (is (thrown-with-msg? Exception #"qualified keywords" (s/put-def s/empty-session :b [:steps "x"]))))

(deftest launching
  (let [sess (-> (session :k [:steps {:inst :drum/kick} "x... .... .... .... .... .... .... ...."])
                 (s/begin-bar 3))]
    (testing "a new track starts from its first step at the bar it is picked up"
      (is (= 3 (get-in sess [:tracks :k :launch])))
      (is (= [0] (map :t (events sess 3))))
      (is (= [] (map :t (events sess 4))))
      (is (= [] (events sess 2))))))

(deftest mute-and-solo
  (let [sess (session :k [:steps {:inst :drum/kick} "x"] :h [:steps {:inst :drum/hat} "x"])
        tracks #(set (map :inst (events (s/begin-bar % 0) 0)))]
    (is (= #{:drum/kick :drum/hat} (tracks sess)))
    (is (= #{:drum/hat} (tracks (assoc sess :mute #{:k}))))
    (is (= #{:drum/kick} (tracks (assoc sess :solo #{:k}))))))

(deftest arrangement
  (let [sess (-> s/empty-session
                 (assoc :scenes {:a {:k [:steps {:inst :drum/kick} "x"]}
                                 :b {:k nil :h [:steps {:inst :drum/hat} "x"]}}
                        :arrangement [[:a 2] [:b 1]]
                        :arrangement-start 4))]
    (is (= [nil :a :a :b nil] (map #(s/arrangement-scene sess %) [3 4 5 6 7])))
    (let [at-6 (reduce s/begin-bar sess [4 5 6])]
      (is (= #{:h} (set (keys (:tracks at-6)))))
      (is (= 6 (get-in at-6 [:tracks :h :launch]))))))

(deftest snapshots-are-scenes
  (let [sess (session :k [:steps {:inst :drum/kick} "x"])]
    (is (= {:k [:steps {:inst :drum/kick} "x"]} (get-in (s/snapshot sess :one) [:scenes :one])))))
