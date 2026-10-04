(ns chaploud.groove
  (:require [chaploud.groove.engine.output :as output]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.io :as io]
            [chaploud.groove.library :as library]
            [chaploud.groove.live :as live]
            [chaploud.groove.notation :as notation]
            [chaploud.groove.session :as session]
            [chaploud.groove.show :as show]
            [clojure.string :as str]))

;; Naming: gestures you make while playing (drum, play, mute, launch, fill, tempo …) have no
;; bang; definitions, files and the transport (put!, scene!, save!, start! …) do.

(defn session [] (:session @live/!state))

(defn start!
  ([] (start! {}))
  ([opts] (live/start! opts) :playing))
(defn stop! [] (live/stop!) :stopped)
(defn status [] (live/status))
(defn devices [] (output/devices))

(defn- commit! [f] (live/commit! f))

;; ---------------------------------------------------------------- tracks

(defn play [track node]
  (commit! #(session/play % track node))
  track)

(defn clear [& tracks]
  (commit! #(update % :tracks (fn [ts] (if (seq tracks) (apply dissoc ts tracks) {}))))
  nil)

(defn hush []
  (commit! #(assoc % :tracks {} :arrangement nil :current-scene nil))
  nil)

(defn- default-drum [track]
  (let [k (keyword "drum" (name track))]
    (or (notation/sound-aliases (name track))
        (when (contains? instruments/builtin k) k)
        (throw (ex-info (str "No drum named " track "; pass :inst, e.g. (drum " track " \"x...\" :inst :rim)")
                        {:track track})))))

(defn- default-synth [track]
  (let [k (keyword "synth" (name track))]
    (if (contains? instruments/builtin k) k :synth/keys)))

(defn drum [track steps & {:as attrs}]
  (play track [:steps (merge {:inst (or (:inst attrs) (default-drum track))} attrs) steps]))

(defn synth [track notes & {:as attrs}]
  (play track [:notes (merge {:inst (or (:inst attrs) (default-synth track))} attrs) notes]))

(defn mini [track pattern & {:as attrs}]
  (play track [:cycle (or attrs {}) pattern]))

(defn- remove-or-reset [ks tracks]
  (if (seq tracks) (apply disj ks tracks) #{}))

(defn mute [& tracks] (commit! #(update % :mute into tracks)) nil)
(defn unmute [& tracks] (commit! #(update % :mute remove-or-reset tracks)) nil)
(defn solo [& tracks] (commit! #(update % :solo into tracks)) nil)
(defn unsolo [& tracks] (commit! #(update % :solo remove-or-reset tracks)) nil)

;; ---------------------------------------------------------------- definitions

(defn put! [k node]
  (commit! #(session/put-def % k node))
  k)

(defn kit! [k roles]
  (commit! #(session/put-kit % k roles))
  k)

(defn instrument! [k params]
  (commit! #(session/put-instrument % k params))
  k)

(defn globals! [m]
  (:globals (commit! #(update % :globals merge m))))

(defn tempo [bpm]
  (globals! {:tempo bpm})
  bpm)

;; ---------------------------------------------------------------- scenes & arrangement

(defn scene! [k tracks]
  (commit! #(assoc-in % [:scenes k] tracks))
  k)

(defn launch [scene]
  (commit! #(session/launch-scene % scene))
  scene)

(defn snap! [scene]
  (commit! #(session/snapshot % scene))
  scene)

(defn- next-bar [] (inc (or (live/current-bar) -1)))

(defn arrange! [plan]
  (commit! #(assoc % :arrangement plan :arrangement-start (next-bar) :current-scene nil))
  plan)

(defn fill
  ([] (fill 1))
  ([bars]
   (let [from (next-bar)]
     (commit! #(update % :fill (fn [f] (into (set (remove (fn [b] (< b from)) f))
                                             (range from (+ from bars))))))
     nil)))

;; ---------------------------------------------------------------- files & views

(defn save! [path]
  (io/write-song! (session) path))

(defn load! [path]
  (let [song (io/read-song path)
        start (next-bar)]
    (commit! (fn [_] (cond-> (io/song->session song)
                       (:arrangement song) (assoc :arrangement-start start))))
    path))

(defn render! [path bars]
  (output/write-wav! (live/render (session) bars) path))

(defn show
  ([x] (show x nil))
  ([x bars]
   (let [s (session)
         node (or (and (simple-keyword? x) (get-in s [:tracks x :node])) x)]
     (println (show/grid node (assoc (library/catalog s) :globals (:globals s) :bars bars))))))

;; ---------------------------------------------------------------- discovery

(defn- entries []
  (let [{:keys [defs kits instruments]} (library/catalog (session))]
    (concat
     (for [k (keys defs)] (merge {:name k :kind :part} (library/about k)))
     (for [k (cons :kit/default (keys kits))] (merge {:name k :kind :kit} (library/about k)))
     (for [[k v] (merge instruments/builtin instruments)]
       {:name k :kind :instrument :doc (name (:voice v (:base v)))}))))

(defn browse
  ([]
   (doseq [[ns es] (sort-by key (group-by (comp namespace :name) (entries)))]
     (println (format "  %-12s %3d  %s" ns (count es)
                      (str/join " " (take 6 (sort (map (comp name :name) es)))))))
   (println "\n(browse \"term\") searches names, tags and descriptions; (audition :beat/house) plays one."))
  ([term]
   (let [t (str/lower-case (name term))
         hits (filter (fn [{:keys [name tags doc]}]
                        (some #(str/includes? (str/lower-case (str %)) t)
                              (concat [name doc] tags)))
                      (entries))]
     (doseq [{:keys [name tags doc tempo]} (sort-by (comp str :name) hits)]
       (println (format "  %-20s %-34s %s" name (str/join " " (sort (map clojure.core/name tags)))
                        (str (or doc "") (when tempo (str " (" tempo " BPM)")))))))))

(defn audition [part]
  (if part
    (do (play :audition (if (keyword? part) [part] part))
        (when-not (live/current-bar) (start!))
        part)
    (clear :audition)))
