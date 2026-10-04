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

(deftest validation-builds-every-voice
  (doseq [[label inst] [["an unknown voice type" {:voice :boing :bus :drums :gain 1.0 :length 0.5}]
                        ["a missing parameter" {:voice :kick :bus :drums :gain 1.0 :length 0.5}]
                        ["a zero decay" {:base :drum/kick :decay 0}]
                        ["a zero filter decay" {:base :synth/acid :fdecay 0.0}]
                        ["a zero drive" {:base :synth/acid :drive 0}]]]
    (is (thrown? Exception
                 (s/validate! (-> (s/put-instrument s/empty-session :my/x inst)
                                  (s/play :x [:notes {:inst :my/x} [0]]))))
        label)))

(deftest validation-sees-past-the-first-bars-and-past-chance
  (is (thrown? Exception (s/validate! (session :x [:seq [:rep 40 [:steps {:inst :drum/kick} "x"]]
                                                   [:steps {:inst :nope/x} "x"]]))))
  (is (thrown? Exception (s/validate! (session :x [:steps {:inst :nope/x :prob 0.01} "x"]))))
  (is (thrown? Exception (s/validate! (session :x [:steps {:inst :nope/x :if :fill} "x"]))))
  (is (thrown-with-msg? Exception #":ratchet" (s/validate! (session :x [:steps {:inst :drum/kick :ratchet 0} "x"]))))
  (is (thrown-with-msg? Exception #"Unknown :if" (s/validate! (session :x [:steps {:inst :drum/kick :if [1 0]} "x"])))))

(deftest validation-covers-tempo-scenes-and-arrangement
  (is (thrown-with-msg? Exception #":tempo" (s/validate! (assoc-in s/empty-session [:globals :tempo] 0))))
  (is (thrown-with-msg? Exception #"Scene :c: Track :k"
                        (s/validate! (assoc s/empty-session :scenes {:c {:k [:steps {:inst :nope/x} "x"]}}))))
  (is (thrown-with-msg? Exception #"Arrangement step"
                        (s/validate! (assoc s/empty-session :scenes {:a {}} :arrangement [[:a 4] [:nope 2]])))))

(deftest rewinding-restarts-the-timeline
  (let [sess (-> (session :k [:steps {:inst :drum/kick} "x"])
                 (assoc :arrangement [[:a 1]] :arrangement-start 37 :fill #{40} :current-scene :a)
                 (s/begin-bar 3)
                 s/rewind)]
    (is (nil? (get-in sess [:tracks :k :launch])))
    (is (= [0 #{} nil] [(:arrangement-start sess) (:fill sess) (:current-scene sess)]))))
