(ns chaploud.groove.render
  (:require [chaploud.groove.engine.output :as output]
            [chaploud.groove.io :as io]
            [chaploud.groove.live :as live]
            [chaploud.groove.session :as session]
            [clojure.string :as str]))

(defn song-bars [{:keys [arrangement]}]
  (if (seq arrangement)
    (reduce + (map second arrangement))
    8))

(defn render-file [path bars out]
  (let [sess (-> (io/read-song path)
                 (io/song->session session/empty-session)
                 (cond-> (:arrangement (io/read-song path)) (assoc :arrangement-start 0)))
        st {:session sess :compiled (session/validate! sess)}]
    (output/write-wav! (live/render st (or bars (song-bars sess))) out)))

(defn -main [path & [bars out]]
  (let [out (or out (str "out/" (str/replace (.getName (java.io.File. ^String path)) #"\.edn$" "") ".wav"))]
    (println (render-file path (some-> bars parse-long) out))
    (shutdown-agents)))
