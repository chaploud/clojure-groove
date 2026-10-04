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
  (is (= [[0 :drum/kick] [1/4 :drum/snare] [1/2 :drum/kick] [3/4 :drum/snare]] (onsets "bd sd bd sd" 0 1)))
  (is (= [[0 :drum/kick] [1/2 :drum/snare] [3/4 :drum/snare]] (onsets "bd [sd sd]" 0 1)))
  (is (= [[0 :drum/hat] [1/4 :drum/hat] [1/2 :drum/hat] [3/4 :drum/hat]] (onsets "hh*4" 0 1)))
  (testing "alternation advances once per cycle, nested alternation per visit"
    (is (= [[0 :drum/kick] [1 :drum/snare] [2 :drum/kick]] (onsets "<bd sd>" 0 3)))
    (is (= [1 2 3 2] (map second (onsets "<<1 3> 2>" 0 4)))))
  (is (= [[0 :drum/kick] [3/8 :drum/kick] [3/4 :drum/kick]] (onsets "bd(3,8)" 0 1)))
  (is (= #{[0 :drum/kick] [0 :drum/hat] [1/2 :drum/hat]} (set (onsets "bd, hh*2" 0 1))))
  (is (= [[0 1] [1/3 1] [2/3 2]] (onsets "1!2 2" 0 1)))
  (is (= [[0 1] [3/4 2]] (onsets "1@3 2" 0 1)))
  (is (= [] (onsets "hh*8?1" 0 1)))
  (is (= 8 (count (onsets "hh*8?0" 0 1))))
  (is (= [[0 1] [2 1]] (onsets "1/2" 0 3)))
  (is (thrown-with-msg? Exception #"Missing \]" (n/parse-mini "[bd sd")))
  (is (thrown-with-msg? Exception #"Unknown word" (n/parse-mini "bd zz"))))

(deftest euclid
  (is (= [true false false true false false true false] (n/euclid 3 8)))
  (is (= 5 (count (filter true? (n/euclid 5 16 2))))))
