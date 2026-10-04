(ns chaploud.groove
  (:require [chaploud.groove.engine.output :as output]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.io :as io]
            [chaploud.groove.live :as live]
            [chaploud.groove.notation :as notation]
            [chaploud.groove.session :as session]
            [chaploud.groove.show :as show]))

(defn session [] (:session @live/!state))

(defn start! [] (live/start!) :playing)
(defn stop! [] (live/stop!) :stopped)

(defn- commit! [f] (live/commit! f) nil)

;; ---------------------------------------------------------------- tracks

(defn play! [track node]
  (commit! #(session/play % track node))
  track)

(defn clear! [& tracks]
  (commit! #(update % :tracks (fn [ts] (apply dissoc ts tracks)))))

(defn hush []
  (commit! #(assoc % :tracks {} :arrangement nil :current-scene nil)))

(defn- default-drum [track]
  (let [k (keyword "drum" (name track))]
    (cond
      (contains? instruments/builtin k) k
      (notation/sound-aliases (name track)) (notation/sound-aliases (name track))
      :else (throw (ex-info (str "No drum named " track "; pass :inst, e.g. (drum " track " \"x...\" :inst :drum/rim)")
                            {:track track})))))

(defn- default-synth [track]
  (let [k (keyword "synth" (name track))]
    (if (contains? instruments/builtin k) k :synth/keys)))

(defn drum [track steps & {:as attrs}]
  (play! track [:steps (merge {:inst (or (:inst attrs) (default-drum track))} attrs) steps]))

(defn synth [track notes & {:as attrs}]
  (play! track [:notes (merge {:inst (or (:inst attrs) (default-synth track))} attrs) notes]))

(defn mini [track pattern & {:as attrs}]
  (play! track [:cycle (or attrs {}) pattern]))

(defn mute [& tracks] (commit! #(update % :mute into tracks)))
(defn unmute [& tracks] (commit! #(update % :mute (fn [m] (reduce disj m (or (seq tracks) m))))))
(defn solo [& tracks] (commit! #(update % :solo into tracks)))
(defn unsolo [& tracks] (commit! #(update % :solo (fn [s] (reduce disj s (or (seq tracks) s))))))

;; ---------------------------------------------------------------- definitions

(defn put! [k node]
  (commit! #(session/put-def % k node))
  k)

(defn instrument! [k params]
  (commit! #(session/put-instrument % k params))
  k)

(defn globals! [m]
  (commit! #(update % :globals merge m))
  (:globals (session)))

(defn tempo [bpm]
  (globals! {:tempo bpm})
  bpm)

;; ---------------------------------------------------------------- scenes & arrangement

(defn scene! [k tracks]
  (commit! #(assoc-in % [:scenes k] tracks))
  k)

(defn launch! [scene]
  (commit! #(session/launch-scene % scene))
  scene)

(defn snap! [scene]
  (commit! #(session/snapshot % scene))
  scene)

(defn- next-bar [] (inc (or (live/current-bar) -1)))

(defn arrange! [plan]
  (commit! #(assoc % :arrangement plan :arrangement-start (next-bar) :current-scene nil))
  plan)

(defn fill!
  ([] (fill! 1))
  ([bars]
   (let [from (next-bar)]
     (commit! #(update % :fill (fn [f] (into (set (remove (fn [b] (< b from)) f))
                                             (range from (+ from bars)))))))))

;; ---------------------------------------------------------------- files & views

(defn save! [path]
  (io/write-song! (session) path))

(defn load! [path]
  (let [song (io/read-song path)
        start (next-bar)]
    (commit! (fn [_] (cond-> (io/song->session song session/empty-session)
                       (:arrangement song) (assoc :arrangement-start start))))
    path))

(defn render!
  ([path bars] (render! path bars @live/!state))
  ([path bars state]
   (output/write-wav! (live/render state bars) path)))

(defn show
  ([x] (show x nil))
  ([x bars]
   (let [s (session)
         node (cond
                (and (simple-keyword? x) (get-in s [:tracks x])) (get-in s [:tracks x :node])
                :else x)]
     (println (show/grid node (assoc (select-keys s [:defs :globals :instruments]) :bars bars))))))
