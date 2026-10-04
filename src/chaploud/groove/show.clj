(ns chaploud.groove.show
  (:require [chaploud.groove.expand :as expand]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.pitch :as pitch]
            [chaploud.groove.query :as query]
            [clojure.string :as str]))

(def ^:private note-names ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn- row-label [{:keys [inst midi]}]
  (if midi
    (str (note-names (mod midi 12)) (dec (quot midi 12)))
    (name inst)))

(defn grid [node {:keys [defs globals instruments bars]}]
  (let [expanded (expand/expand node defs globals [])
        bars (or bars (max 1 (long (Math/ceil (double (:len expanded))))))
        events (for [bar (range bars)
                     e (query/bar-events expanded bar {:seed [:show bar]})]
                 (let [p (pitch/resolve-midi (merge (instruments/resolve-instrument instruments (:inst e)) e))]
                   (assoc p :col (+ (* 16 bar) (long (Math/floor (* 16 (double (:t e))))))
                          :label (row-label p))))
        rows (->> (group-by :label events)
                  (sort-by (fn [[_ es]] (- (or (:midi (first es)) 1000)))))
        width (reduce max 4 (map (comp count first) rows))]
    (str/join "\n"
              (for [[label es] rows
                    :let [hits (into {} (map (juxt :col #(if (>= (:vel % 0.8) 0.95) "X" "x"))) es)]]
                (str (format (str "%-" width "s ") label)
                     (str/join (for [c (range (* 16 bars))]
                                 (str (when (zero? (mod c 16)) "|")
                                      (hits c (if (zero? (mod c 4)) "." "·")))))
                     "|")))))
