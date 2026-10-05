(ns chaploud.groove.library-test
  (:require [chaploud.groove.expand :as expand]
            [chaploud.groove.library :as library]
            [chaploud.groove.session :as s]
            [clojure.test :refer [deftest is testing]]))

(def catalog (library/catalog s/empty-session))

(deftest every-bundled-part-plays
  (doseq [k (keys (:defs catalog))]
    (is (map? (s/validate! (s/play s/empty-session :t k))) (str k))))

(deftest parts-fill-whole-bars
  (doseq [k (keys (:defs catalog))
          :when (#{"beat" "fill" "bass" "lead" "arp" "prog"} (namespace k))]
    (is (integer? (:len (expand/expand k (:defs catalog)))) (str k))))

(deftest every-part-and-kit-is-described
  (doseq [k (concat (keys (:defs catalog)) (keys (:kits catalog)))]
    (is (string? (:doc (library/about k))) (str k))))

(deftest kits-swap-the-instruments-behind-roles
  (let [inst-of (fn [kit]
                  (let [sess (s/play s/empty-session :t [:steps {:inst :bd :kit kit} "x"])]
                    (:pitch (first (s/bar-events (s/begin-bar sess 0) (s/validate! sess) 0)))))]
    (is (not= (inst-of :kit/default) (inst-of :kit/tr808))))
  (testing "a session's own kit can extend a bundled one"
    (let [sess (-> (s/put-kit s/empty-session :my/kit {:base :kit/tr909 :bd :drum/tom-low})
                   (s/play :t [:steps {:inst :bd :kit :my/kit} "x"]))]
      (is (= 82.0 (:pitch (first (s/bar-events (s/begin-bar sess 0) (s/validate! sess) 0)))))))
  (is (thrown-with-msg? Exception #"Kit :kit/default has no :zz"
                        (s/validate! (s/play s/empty-session :t [:steps {:inst :zz} "x"])))))

(deftest misspelled-references-suggest-the-closest-name
  (is (thrown-with-msg? Exception #"did you mean :beat/house\?"
                        (s/validate! (s/play s/empty-session :t :beat/hous)))))

(deftest progressions-sound-the-same-under-any-global-scale
  (doseq [k (filter #(= "prog" (namespace %)) (keys (:defs catalog)))]
    (let [midis (fn [scale]
                  (let [sess (-> (s/play s/empty-session :t k) (assoc :globals {:tempo 120 :scale scale}))]
                    (map :midi (s/bar-events (s/begin-bar sess 0) (s/validate! sess) 0))))]
      (is (= (midis :major) (midis :minor)) (str k)))))
