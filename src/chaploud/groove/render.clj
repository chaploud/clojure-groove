(ns chaploud.groove.render
  (:require [chaploud.groove.engine.output :as output]
            [chaploud.groove.io :as io]
            [chaploud.groove.live :as live]
            [clojure.string :as str]))

(defn song-bars [{:keys [arrangement]}]
  (if (seq arrangement)
    (reduce + (map second arrangement))
    8))

(defn render-file [path bars out]
  (let [session (io/song->session (io/read-song path))]
    (output/write-wav! (live/render session (or bars (song-bars session))) out)))

(defn -main [path & [bars out]]
  (let [out (or out (str "out/" (str/replace (.getName (java.io.File. ^String path)) #"\.edn$" "") ".wav"))]
    (println (render-file path (some-> bars parse-long) out))
    (shutdown-agents)))
