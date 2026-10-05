(ns ^:no-doc chaploud.groove.render
  (:require [chaploud.groove.engine.output :as output]
            [chaploud.groove.io :as io]
            [chaploud.groove.live :as live]
            [chaploud.groove.session :as session]
            [clojure.string :as str]))

(defn default-out [song]
  (str "out/" (str/replace (.getName (java.io.File. ^String song)) #"\.edn$" "") ".wav"))

(defn render-file [song bars out]
  (let [{:keys [session]} (io/load-song song)]
    (output/write-wav! (live/render session (or bars (session/arrangement-bars session) 8)) out)))
