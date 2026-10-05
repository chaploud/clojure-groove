(ns chaploud.groove.pitch-test
  (:require [chaploud.groove.pitch :as p]
            [clojure.test :refer [deftest is]]))

(deftest note-names
  (is (= 60 (p/note->midi :c4 4)))
  (is (= 61 (p/note->midi "c#4" 4)))
  (is (= 61 (p/note->midi :cs4 4)))
  (is (= 39 (p/note->midi :eb2 4)))
  (is (= 69 (p/note->midi :a 4))))

(deftest degrees-wrap-octaves
  (is (= 57 (p/degree->midi 0 {:root :a :scale :minor :octave 3})))
  (is (= 60 (p/degree->midi 2 {:root :a :scale :minor :octave 3})))
  (is (= 69 (p/degree->midi 7 {:root :a :scale :minor :octave 3})))
  (is (= 55 (p/degree->midi -1 {:root :a :scale :minor :octave 3}))))

(deftest most-specific-pitch-key-wins
  (is (= 40 (:midi (p/resolve-midi {:midi 40 :note :c4 :degree 3}))))
  (is (= 60 (:midi (p/resolve-midi {:note :c4 :degree 3}))))
  (is (= 72 (:midi (p/resolve-midi {:note :c4 :transpose 12}))))
  (is (nil? (:midi (p/resolve-midi {:inst :drum/kick})))))

(defn- midis [event] (mapv :midi (p/resolve-pitches event)))

(deftest roman-numerals-build-chords-on-scale-degrees
  (let [a-minor {:root :a :scale :minor :octave 3}]
    (is (= [57 60 64] (midis (assoc a-minor :roman :i))))
    (is (= [65 69 72] (midis (assoc a-minor :roman :VI))))
    (is (= [57 60 64 67] (midis (assoc a-minor :roman :i7))))
    (is (= [64 68 71] (midis (assoc a-minor :roman :V))) "uppercase is major even off the scale")
    (is (= [67 71 74] (midis (assoc a-minor :roman :bVII :scale :major))))
    (is (nil? (p/parse-roman :ix)))
    (is (nil? (p/parse-roman :c4)))))

(deftest voicings-and-inversions
  (let [c7 {:root :c :scale :major :octave 4 :roman :I7}]
    (is (= [60 64 67 70] (midis c7)))
    (is (= [64 67 70 72] (midis (assoc c7 :inv 1))))
    (is (= [60 67 70 76] (midis (assoc c7 :voicing :open))))
    (is (= [55 60 64 70] (midis (assoc c7 :voicing :drop2))))
    (is (= [60] (midis (assoc c7 :voicing :root))))
    (is (= [67] (midis (assoc c7 :arp-index 2))))))

(deftest accidentals-count-from-the-major-scale
  (is (= [70 74 77] (midis {:root :c :scale :minor :octave 4 :roman :bVII})))
  (is (= [70 74 77] (midis {:root :c :scale :major :octave 4 :roman :bVII}))))

(deftest bass-roots-stay-near-the-tonic
  (let [roots (fn [romans] (map #(first (midis {:root :c :scale :minor :octave 2 :voicing :root :roman %})) romans))]
    (is (= [36 34 32 31] (roots [:i :VII :VI :V])) "the Andalusian roots walk down")
    (is (every? #(<= 30 % 42) (roots [:i :ii :III :iv :v :VI :VII])))))
