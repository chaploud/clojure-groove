(ns chaploud.groove.expand-test
  (:require [chaploud.groove.expand :as x]
            [clojure.test :refer [deftest is testing]]))

(def defs
  {:clip/kick [:steps {:inst :drum/kick :vel 0.7} "x..."]
   :clip/bass [:notes {:octave 2} [0 3]]
   :sec/a [:par {:root :a} :clip/kick [:clip/bass {:octave 1}]]
   :loop/a [:seq :loop/b]
   :loop/b [:par [:loop/a]]})

(defn- err [node]
  (try (x/expand node defs) nil
       (catch clojure.lang.ExceptionInfo e [(ex-message e) (:path (ex-data e))])))

(deftest cascade
  (let [{[kick bass] :children} (x/expand [:sec/a {:scale :minor}] defs)]
    (testing "attributes flow from parents to children"
      (is (= :a (get-in kick [:attrs :root])))
      (is (= :minor (get-in bass [:attrs :scale]))))
    (testing "attributes at a reference site override the referenced definition"
      (is (= 1 (get-in bass [:attrs :octave]))))
    (testing "a definition's own attributes override inherited ones"
      (is (= 0.7 (get-in (x/expand [:par {:vel 0.2} :clip/kick] defs) [:children 0 :attrs :vel]))))))

(deftest lengths
  (is (= 1/4 (:len (x/expand :clip/kick defs))))
  (is (= 1/8 (:len (x/expand :clip/bass defs))))
  (is (= 3/8 (:len (x/expand [:seq :clip/kick :clip/bass] defs))))
  (is (= 1/4 (:len (x/expand [:par :clip/kick :clip/bass] defs))))
  (is (= 1 (:len (x/expand [:rep 4 :clip/kick] defs)))))

(deftest errors-carry-paths
  (is (= ["Circular reference through :loop/a" [:loop/a :seq 0 :loop/b :par 0 :loop/a]] (err :loop/a)))
  (is (= ["Undefined reference :clip/nope" [:par 1 :clip/nope]] (err [:par :clip/kick :clip/nope])))
  (is (= ["Unexpected character \\q at column 2 of step string" [:seq 0 :steps]] (err [:seq [:steps "xq"]])))
  (is (re-find #"Unknown transform" (first (err [:fx [[:wobble]] :clip/kick]))))
  (is (re-find #"Unknown node type" (first (err [:drums "x..."])))))
