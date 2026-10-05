(ns chaploud.groove.notation-test
  (:require [chaploud.groove.notation :as n]
            [clojure.test :refer [deftest is testing]]))

(defn- onsets [pattern lo hi]
  (mapv (juxt :t (comp #(or (:inst %) (:degree %) (:note %)) :event))
        (n/mini-events (n/parse-mini pattern) lo hi)))

(deftest step-strings
  (is (= [{} nil {:vel 1.0} nil {} nil nil nil] (n/parse-steps "x.X- |o...")))
  (is (= [nil {} {:vel 0.5} {:if :fill} {:vel 1.0}] (n/parse-steps [:_ :x 0.5 {:if :fill} :X])))
  (is (thrown-with-msg? Exception #"column 3" (n/parse-steps "x.q"))))

(deftest note-vectors
  (testing "duration keywords persist only within the vector"
    (is (= [[0 1/8 {:degree 0}] [1/8 1/16 {:degree 2}] [3/16 1/16 nil] [1/4 3/8 {:note :c4}]]
           (mapv (juxt :t :dur :event) (n/parse-notes [:e 0 :s 2 :_ :q. :c4] 1/16)))))
  (testing "ratios set the duration and sets are chords"
    (is (= [[0 1/4 {:chord [{:degree 0} {:degree 4}]}]]
           (mapv (juxt :t :dur :event) (n/parse-notes [1/4 (sorted-set 0 4)] 1/16)))))
  (is (thrown-with-msg? Exception #"Invalid note" (n/parse-notes [0 :zz] 1/16))))

(deftest mini-notation
  (is (= [[0 :bd] [1/4 :sd] [1/2 :bd] [3/4 :sd]] (onsets "bd sd bd sd" 0 1)))
  (is (= [[0 :bd] [1/2 :sd] [3/4 :sd]] (onsets "bd [sd sd]" 0 1)))
  (is (= [[0 :hh] [1/4 :hh] [1/2 :hh] [3/4 :hh]] (onsets "hh*4" 0 1)))
  (testing "alternation advances once per cycle, nested alternation per visit"
    (is (= [[0 :bd] [1 :sd] [2 :bd]] (onsets "<bd sd>" 0 3)))
    (is (= [1 2 3 2] (map second (onsets "<<1 3> 2>" 0 4)))))
  (is (= [[0 :bd] [3/8 :bd] [3/4 :bd]] (onsets "bd(3,8)" 0 1)))
  (is (= #{[0 :bd] [0 :hh] [1/2 :hh]} (set (onsets "bd, hh*2" 0 1))))
  (is (= [[0 1] [1/3 1] [2/3 2]] (onsets "1!2 2" 0 1)))
  (is (= [[0 1] [3/4 2]] (onsets "1@3 2" 0 1)))
  (is (= [] (onsets "hh*8?1" 0 1)))
  (is (= 8 (count (onsets "hh*8?0" 0 1))))
  (is (= [[0 1] [2 1]] (onsets "1/2" 0 3)))
  (is (thrown-with-msg? Exception #"Missing \]" (n/parse-mini "[bd sd")))
  (is (thrown-with-msg? Exception #"Unknown word" (n/parse-mini "bd zz"))))

(deftest bare-modifiers-do-not-swallow-the-next-number
  (is (= [[0 0] [1/2 2] [3/4 3]] (remove #(= 1 (second %)) (onsets "0 1? 2 3" 0 1))))
  (is (= [[0 0] [1/4 2] [1/2 2] [3/4 4]] (onsets "0 2! 4" 0 1))))

(deftest euclid-matches-bjorklund
  (is (= [true false false true false false true false] (n/euclid 3 8)))
  (is (= [true false true true false true true false] (n/euclid 5 8)))
  (is (= (vec (take 8 (drop 2 (cycle (n/euclid 3 8))))) (n/euclid 3 8 2)))
  (is (thrown? Exception (n/euclid 9 8))))

(deftest triplets-do-not-shadow-note-names
  (is (= [[0 1/12 {:degree 0}] [1/12 1/12 {:note :e3}]]
         (mapv (juxt :t :dur :event) (n/parse-notes [:et 0 :e3] 1/16)))))

(deftest glides-start-from-the-previous-note-and-wrap
  (let [items (n/parse-notes [:s 0 {:degree 4 :glide true} 2 {:degree 7 :glide true}] 1/16)]
    (is (= {:degree 0} (get-in items [1 :event :glide-from])))
    (is (= {:degree 2} (get-in items [3 :event :glide-from])))
    (is (nil? (get-in items [0 :event :glide-from])))))

(deftest bar-by-bar-bodies-must-add-up
  (is (= [[0 1/2] [1/2 1/2] [1 1]] (mapv (juxt :t :dur) (n/parse-notes [[:h 0 2] [:w 4]] 1/16))))
  (is (thrown-with-msg? Exception #"Bar 2 lasts 3/4" (n/parse-notes [[:w 0] [:h 2 :q 4]] 1/16)))
  (is (= 32 (count (n/parse-step-bars ["x... x... x... x..." "x... x... x.x. x.x."] 1/16))))
  (is (thrown-with-msg? Exception #"Bar 1 lasts 1/2" (n/parse-step-bars ["x... x..."] 1/16))))
