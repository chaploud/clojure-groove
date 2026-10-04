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
