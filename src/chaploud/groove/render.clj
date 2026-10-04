(ns chaploud.groove.render
  (:require [chaploud.groove.engine.output :as output]
            [chaploud.groove.io :as io]
            [chaploud.groove.live :as live]
            [clojure.string :as str]))

(defn song-bars [{:keys [arrangement]}]
  (if (seq arrangement)
    (reduce + (map second arrangement))
    8))

(defn default-out [song]
  (str "out/" (str/replace (.getName (java.io.File. ^String song)) #"\.edn$" "") ".wav"))

(defn render-file [song bars out]
  (let [session (io/song->session (io/read-song song))]
    (output/write-wav! (live/render session (or bars (song-bars session))) out)))
