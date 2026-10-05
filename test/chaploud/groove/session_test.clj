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

(def ^:private kick [:steps {:inst :drum/kick} "x"])
(def ^:private hat [:steps {:inst :drum/hat} "x"])

(deftest sections-are-complete-and-inherit
  (let [sections {:a {:k kick :h hat}
                  :b {:base :a :h nil :r [:steps {:inst :drum/rim} "x"]}}]
    (is (= {:k kick :r [:steps {:inst :drum/rim} "x"]} (s/resolve-section sections :b)))
    (testing "launching a section stops every track it does not name"
      (is (= #{:k :r} (set (keys (:tracks (s/launch-section (assoc (session :old hat) :sections sections) :b))))))))
  (is (thrown-with-msg? Exception #"Circular section :base" (s/resolve-section {:a {:base :b} :b {:base :a}} :a)))
  (is (thrown-with-msg? Exception #"Unknown section :nope" (s/resolve-section {:a {:base :nope}} :a))))

(deftest arrangement
  (let [sess (-> s/empty-session
                 (assoc :sections {:a {:k kick} :b {:h hat}}
                        :arrangement [[:a 2] [:b 1] [:b 2 {:fill 1}]]
                        :arrangement-start 4))
        step #(:section (s/arrangement-step sess %))]
    (is (= [nil :a :a :b :b :b nil] (map step [3 4 5 6 7 8 9])))
    (let [at-6 (reduce s/begin-bar sess [4 5 6])
          at-7 (s/begin-bar at-6 7)]
      (is (= #{:h} (set (keys (:tracks at-6)))))
      (is (= 6 (get-in at-6 [:tracks :h :launch])))
      (testing "a step restarts its tracks even when the same section repeats"
        (is (= 7 (get-in at-7 [:tracks :h :launch])))))
    (testing "{:fill n} turns on :if :fill steps in the step's last n bars"
      (let [roll (-> sess
                     (assoc :sections {:a {:k kick} :b {:roll [:steps {:inst :drum/snare :if :fill} "x..............."]}}))
            snares (fn [bar] (count (events (reduce s/begin-bar roll (range 4 (inc bar))) bar)))]
        (is (= [0 0 1] (map snares [6 7 8])))))))

(deftest one-hits-at-section-starts
  (let [sess (-> s/empty-session
                 (assoc :sections {:a {:crash [:steps {:inst :drum/crash :if :1st} "x..............."]}}
                        :arrangement [[:a 4] [:a 4]]
                        :arrangement-start 0))
        crashes (fn [bar] (count (events (reduce s/begin-bar sess (range (inc bar))) bar)))]
    (is (= [1 0 0 0 1 0] (map crashes (range 6))))))

(deftest snapshots-are-sections
  (let [sess (session :k kick)]
    (is (= {:k kick} (get-in (s/snapshot sess :one) [:sections :one])))))

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

(deftest validation-covers-tempo-sections-and-arrangement
  (is (thrown-with-msg? Exception #":tempo" (s/validate! (assoc-in s/empty-session [:globals :tempo] 0))))
  (is (thrown-with-msg? Exception #"Section :c: Track :k"
                        (s/validate! (assoc s/empty-session :sections {:c {:k [:steps {:inst :nope/x} "x"]}}))))
  (is (thrown-with-msg? Exception #"Arrangement step"
                        (s/validate! (assoc s/empty-session :sections {:a {}} :arrangement [[:a 4] [:nope 2]]))))
  (is (thrown-with-msg? Exception #":fill is a number of bars"
                        (s/validate! (assoc s/empty-session :sections {:a {}} :arrangement [[:a 4 {:fill 5}]]))))
  (is (thrown-with-msg? Exception #"unknown options"
                        (s/validate! (assoc s/empty-session :sections {:a {}} :arrangement [[:a 4 {:fil 1}]])))))

(deftest rewinding-restarts-the-timeline
  (let [sess (-> (session :k [:steps {:inst :drum/kick} "x"])
                 (assoc :arrangement [[:a 1]] :arrangement-start 37 :fill #{40} :current-step 0)
                 (s/begin-bar 3)
                 s/rewind)]
    (is (nil? (get-in sess [:tracks :k :launch])))
    (is (= [0 #{} nil] [(:arrangement-start sess) (:fill sess) (:current-step sess)]))))

(deftest chords-take-the-octave-of-the-instrument
  (let [sess (session :b [:notes {:inst :synth/bass} [:w #{0 2 4}]])]
    (is (= [36 40 43] (sort (map :midi (events (s/begin-bar sess 0) 0)))))))

(deftest glide-feel-and-sends
  (testing "a glide carries the pitch it slides from"
    (let [sess (session :b [:notes {:inst :synth/acid :root :c :scale :minor} [:s 0 {:degree 4 :glide true}]])]
      (is (= [nil 36] (take 2 (map :glide-from-midi (events (s/begin-bar sess 0) 0)))))))
  (testing "swing can act on eighths"
    (let [ts #(map :t (events (s/begin-bar (session :h [:steps {:inst :drum/hat :swing 1/2 :swing-step %} "xxxxxxxx xxxxxxxx"]) 0) 0))]
      (is (= [0 3/32 1/8] (take 3 (ts 1/16))))
      (is (= [0 1/16 3/16 3/16] (take 4 (ts 1/8))) "the second eighth moves by half an eighth")))
  (testing "humanize stays small and is repeatable"
    (let [sess (session :h [:steps {:inst :drum/hat :humanize 1.0} "xxxx xxxx xxxx xxxx"])
          es (events (s/begin-bar sess 0) 0)]
      (is (= es (events (s/begin-bar sess 0) 0)))
      (is (every? (fn [[e i]] (<= (Math/abs (double (- (:t e) (/ i 16)))) 1/64)) (map vector (sort-by :t es) (range))))
      (is (not= #{0.8} (set (map :vel es))))))
  (testing "send levels default by bus and can be overridden"
    (let [[d] (events (s/begin-bar (session :k [:steps {:inst :drum/kick} "x"]) 0) 0)
          [p] (events (s/begin-bar (session :p [:notes {:inst :synth/pad :reverb 0.2} [0]]) 0) 0)]
      (is (= [0.0 0.15] [(:delay d) (:reverb d)]))
      (is (= [1.0 0.2] [(:delay p) (:reverb p)])))))

(deftest sends-follow-the-effective-bus
  (let [[e] (events (s/begin-bar (session :l [:notes {:inst :synth/lead :bus :bass} [0]]) 0) 0)]
    (is (= [0.0 0.3] [(:delay e) (:reverb e)]))))

(deftest the-arrangement-ends-in-silence
  (let [sess (-> s/empty-session
                 (assoc :sections {:a {:k [:steps {:inst :drum/kick} "x..............."]}}
                        :arrangement [[:a 2]] :arrangement-start 0))
        at (fn [bar] (reduce s/begin-bar sess (range (inc bar))))]
    (is (= #{:k} (set (keys (:tracks (at 1))))))
    (is (empty? (:tracks (at 2))))))

(deftest send-levels-and-buses-are-checked
  (doseq [bad [{:reverb :big} {:delay nil} {:delay 2.0} {:bus :drum} {:choke "hats"}]]
    (is (thrown? Exception (s/validate! (session :x [:steps (merge {:inst :drum/kick} bad) "x"])))
        (pr-str bad)))
  (is (thrown-with-msg? Exception #":prob must be a number"
                        (s/validate! (session :x [:steps {:inst :drum/kick :prob "0.5"} "x"])))))
