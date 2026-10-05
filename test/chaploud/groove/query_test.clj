(ns chaploud.groove.query-test
  (:require [chaploud.groove.expand :as x]
            [chaploud.groove.query :as q]
            [clojure.test :refer [deftest is testing]]))

(defn- ts
  ([node bar] (ts node bar {}))
  ([node bar opts] (mapv :t (q/bar-events (x/expand node {}) bar opts))))

(deftest looping-and-polymeter
  (is (= [0 1/4 1/2 3/4] (ts [:steps "x..."] 0)))
  (testing "a 3-step pattern keeps its phase across bars"
    (is (= [0 3/16 3/8 9/16 3/4 15/16] (ts [:steps "x.."] 0)))
    (is (= [1/8 5/16 1/2 11/16 7/8] (ts [:steps "x.."] 1))))
  (testing "a two-bar pattern"
    (is (= [0] (ts [:seq [:steps "x..."] [:steps "...."] [:steps "...."] [:steps "...."]
                    [:steps "...."] [:steps "...."] [:steps "...."] [:steps "...."]] 0)))))

(deftest transforms
  (is (= [15/16] (ts [:fx [:rev] [:steps "x... .... .... ...."]] 0)))
  (is (= [0 1/2] (ts [:fx [:fast 2] [:steps "x... .... .... ...."]] 0)))
  (is (= [0] (ts [:fx [:slow 2] [:steps "x... .... x... ...."]] 0)))
  (is (= [0] (ts [:fx [:slow 2] [:steps "x... .... x... ...."]] 1)))
  (testing ":every applies on loop iterations divisible by n"
    (let [node [:fx [:every 2 [:rev]] [:steps "x... .... .... ...."]]]
      (is (= [15/16] (ts node 0)))
      (is (= [0] (ts node 1)))))
  (is (= [7] (map :transpose (q/bar-events (x/expand [:fx [[:transpose 5] [:transpose 2]] [:notes [:w 0]]] {}) 0 {})))))

(deftest step-conditions
  (let [node [:steps [{:if :fill} {:if :!fill} {:if :1st} {:if [2 2]}]]]
    (testing "four loops of four steps: :1st only on the first, [2 2] on every second"
      (is (= [1/16 1/8 5/16 7/16 9/16 13/16 15/16] (ts node 0))))
    (is (= [0 1/8 1/4 7/16 1/2 3/4 15/16] (ts node 0 {:fill? true}))))
  (is (= [] (ts [:steps (into [{:prob 0}] (repeat 15 nil))] 0)))
  (is (= [0] (ts [:steps (into [{:prob 1}] (repeat 15 nil))] 0))))

(deftest queries-compose-across-window-splits
  (doseq [node [[:steps "x.x x..x."]
                [:fx [[:rev] [:fast 2]] [:notes [:e 0 2 :s 3 4 5]]]
                [:fx [:slow 2] [:seq [:steps "x..."] [:notes [:q 1 2]]]]
                [:rep 3 [:cycle "bd [sd sd] <hh oh>"]]]
          :let [e (x/expand node {})
                len (:len e)
                whole (vec (q/query e 0 len 3))]
          [a b] [[1/7 2/3] [1/16 1/2] [1/3 3/4]]
          :let [cuts [0 (* a len) (* b len) len]]]
    (is (= (sort-by :t whole)
           (sort-by :t (mapcat (fn [[lo hi]] (q/query e lo hi 3)) (partition 2 1 cuts))))
        (pr-str node))))

(deftest a-five-quarter-loop-plays-every-note-once-per-loop
  (let [node [:notes [:q 0 1 2 3 4]]
        degrees (mapcat #(map :degree (q/bar-events (x/expand node {}) % {})) (range 5))]
    (is (= {0 4 1 4 2 4 3 4 4 4} (frequencies degrees)))))

(deftest arpeggios-walk-the-chord
  (let [arp (fn [order] (mapv :arp-index (q/bar-events (x/expand [:fx [:arp order 1/8] [:notes [:w :I7]]] {}) 0 {})))]
    (is (= [0 1 2 3 0 1 2 3] (arp :up)))
    (is (= [3 2 1 0 3 2 1 0] (arp :down)))
    (is (= [0 1 2 3 2 1 0 1] (arp :up-down)))
    (is (every? #(<= 0 % 3) (arp :random)))
    (is (= [0 1/8] (take 2 (map :t (q/bar-events (x/expand [:fx [:arp :up 1/8] [:notes [:w :I7]]] {}) 0 {})))))))

(deftest struct-plays-a-rhythm-through-the-notes-underneath
  (let [node [:fx [:struct ".x.x"] [:notes [:h 0 4]]]
        evs (q/bar-events (x/expand node {}) 0 {})]
    (is (= [1/16 3/16 5/16 7/16 9/16 11/16 13/16 15/16] (map :t evs)))
    (is (= [0 0 0 0 4 4 4 4] (map :degree evs)))
    (is (every? #(= 1/16 (:dur %)) evs)))
  (testing "rhythm steps carry their own attributes and the notes keep theirs"
    (let [evs (q/bar-events (x/expand [:fx [:struct [:steps [{:vel 1.0} :_ :_ :_]]] [:notes {:inst :synth/keys} [:w 0]]] {}) 0 {})]
      (is (= [{:inst :synth/keys :degree 0 :vel 1.0}] (map #(select-keys % [:inst :degree :vel]) (take 1 evs))))))
  (testing "rests underneath stay silent"
    (is (= [0] (map :t (q/bar-events (x/expand [:fx [:struct "xxxx xxxx xxxx xxxx"] [:notes [:s 0 :_ :_ :_ :_ :_ :_ :_ :_ :_ :_ :_ :_ :_ :_ :_]]] {}) 0 {}))))))

(deftest struct-takes-only-timing-from-a-note-rhythm
  (let [evs (q/bar-events (x/expand [:fx [:struct [:notes [:q. 9 :e :_ :h :_]]] [:notes [:w 2]]] {}) 0 {})]
    (is (= [[0 3/8 2]] (map (juxt :t :dur :degree) evs)))))
