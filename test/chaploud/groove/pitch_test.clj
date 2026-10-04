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
