(ns chaploud.groove-test
  (:require [chaploud.groove :as g]
            [chaploud.groove.live :as live]
            [chaploud.groove.session :as s]
            [clojure.edn]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each (fn [t]
                      (let [saved @live/!state]
                        (reset! live/!state {:session s/empty-session :compiled {}})
                        (try (t) (finally (reset! live/!state saved))))))

(deftest a-rejected-edit-leaves-the-session-untouched
  (g/drum :kick "x...")
  (let [before @live/!state]
    (is (thrown? Exception (g/play :k [:steps {:inst :drum/nope} "x"])))
    (is (thrown? Exception (g/drum :kick "x..q")))
    (is (identical? before @live/!state))))

(deftest removing-a-referenced-definition-is-rejected
  (g/put! :clip/a [:steps {:inst :drum/kick} "x"])
  (g/play :a :clip/a)
  (is (thrown-with-msg? Exception #"Undefined reference" (g/put! :clip/a nil))))

(deftest default-instruments
  (g/drum :hh "x")
  (g/synth :lead [0])
  (g/synth :foo [0])
  (is (= {:hh :hh :lead :synth/lead :foo :synth/keys}
         (update-vals (:tracks (g/session)) #(get-in % [:node 1 :inst]))))
  (is (thrown-with-msg? Exception #"No drum named :zz" (g/drum :zz "x"))))

(deftest stopped-transport-counts-from-bar-zero
  (g/fill 2)
  (is (= #{0 1} (:fill (g/session)))))

(deftest clearing
  (g/drum :kick "x")
  (g/drum :hh "x")
  (g/clear :kick)
  (is (= #{:hh} (set (keys (:tracks (g/session))))))
  (g/clear)
  (is (empty? (:tracks (g/session)))))

(deftest auditions-are-not-saved
  (g/drum :kick "x")
  (g/play :audition :beat/house)
  (let [path (str (java.io.File/createTempFile "song" ".edn"))]
    (g/save! path)
    (is (= #{:kick} (set (keys (:tracks (clojure.edn/read-string (slurp path)))))))))

(deftest kits-and-definitions-are-checked-when-put
  (is (thrown-with-msg? Exception #"A kit is a map" (g/kit! :my/kit [:bd])))
  (g/kit! :my/kit {:bd :drum/tom-low})
  (g/kit! :my/kit nil)
  (is (empty? (:kits (g/session))))
  (is (thrown-with-msg? Exception #"Circular reference.*use a new one" (g/put! :beat/house [:beat/house {:swing 0.1}])))
  (is (= :clip/later (g/put! :clip/later [:par :clip/not-yet]))))
