(ns chaploud.groove
  "Live-code grooves from the REPL.

  Every function here changes one session: a map of tracks, definitions, sections and globals.
  Changes are validated before they are adopted and, while the transport runs, heard from the
  next bar. Gestures made while playing (`drum`, `play`, `mute`, `launch`, `fill`, `tempo` ...)
  have no bang; definitions, files and the transport (`put!`, `section!`, `save!`, `start!` ...) do.

  See docs/guide.md for a walkthrough and docs/reference.md for the data format."
  (:require [chaploud.groove.engine.output :as output]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.io :as io]
            [chaploud.groove.library :as library]
            [chaploud.groove.live :as live]
            [chaploud.groove.notation :as notation]
            [chaploud.groove.session :as session]
            [chaploud.groove.show :as show]
            [clojure.string :as str]))

(defn session
  "Returns the current session map."
  [] (:session @live/!state))

(defn start!
  "Starts the audio transport. Options: `:device`, a substring of an output name from
  `devices`. Throws if no audio output is available; `render!` works without one."
  ([] (start! {}))
  ([opts] (live/start! opts) :playing))
(defn stop!
  "Stops the transport and rewinds the timeline to bar 0."
  []
  (live/stop!)
  :stopped)
(defn status
  "Returns `{:playing? :bar :errors}`; `:errors` lists problems reported while playing."
  [] (live/status))
(defn devices
  "Returns the names of the audio outputs `start!` can use."
  [] (output/devices))

(defn- commit! [f] (live/commit! f))

;; ---------------------------------------------------------------- tracks

(defn play
  "Plays `node` on `track` (a simple keyword) from the next bar, or stops the track when
  `node` is nil. A track that is already playing keeps its position in the loop."
  [track node]
  (commit! #(session/play % track node))
  track)

(defn clear
  "Stops the given tracks, or every track when called with none. The arrangement keeps running."
  [& tracks]
  (commit! #(update % :tracks (fn [ts] (if (seq tracks) (apply dissoc ts tracks) {}))))
  nil)

(defn hush
  "Stops every track and the arrangement."
  []
  (commit! #(assoc % :tracks {} :arrangement nil :current-step nil))
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

(defn drum
  "Plays a step pattern on `track`, e.g. `(drum :kick \"x... x... x... x...\")`.
  The instrument defaults to the drum named like the track (a kit role such as `:bd` for
  `:kick`); pass `:inst` and any other attributes as keyword arguments."
  [track steps & {:as attrs}]
  (play track [:steps (merge {:inst (or (:inst attrs) (default-drum track))} attrs) steps]))

(defn synth
  "Plays a note vector on `track`, e.g. `(synth :bass [:e 0 :_ 3 5])`. The instrument defaults
  to the synth named like the track, else `:synth/keys`; pass `:inst` and any other attributes
  as keyword arguments."
  [track notes & {:as attrs}]
  (play track [:notes (merge {:inst (or (:inst attrs) (default-synth track))} attrs) notes]))

(defn mini
  "Plays a mini-notation pattern on `track`, e.g. `(mini :drums \"bd*4, [~ cp]*2\")`."
  [track pattern & {:as attrs}]
  (play track [:cycle (or attrs {}) pattern]))

(defn- remove-or-reset [ks tracks]
  (if (seq tracks) (apply disj ks tracks) #{}))

(defn mute
  "Silences tracks without stopping them."
  [& tracks] (commit! #(update % :mute into tracks)) nil)
(defn unmute
  "Unmutes the given tracks, or all of them."
  [& tracks] (commit! #(update % :mute remove-or-reset tracks)) nil)
(defn solo
  "Plays only the soloed tracks."
  [& tracks] (commit! #(update % :solo into tracks)) nil)
(defn unsolo
  "Removes the given tracks from the solo set, or clears it."
  [& tracks] (commit! #(update % :solo remove-or-reset tracks)) nil)

;; ---------------------------------------------------------------- definitions

(defn put!
  "Defines `node` under the qualified keyword `k` for use as a reference, or removes the
  definition when `node` is nil. Definitions shadow bundled parts of the same name."
  [k node]
  (commit! #(session/put-def % k node))
  k)

(defn kit!
  "Defines a kit: a map from drum roles (`:bd :sd :hh` ...) to instruments, optionally with
  `:base` naming a kit to extend. nil removes it."
  [k roles]
  (commit! #(session/put-kit % k roles))
  k)

(defn instrument!
  "Defines an instrument from synthesis parameters, usually `{:base :synth/acid ...}` plus
  overrides. `(describe :synth/acid)` lists the parameters."
  [k params]
  (commit! #(session/put-instrument % k params))
  k)

(defn globals!
  "Merges `m` into the globals, the root of the attribute cascade (`:tempo`, `:root`,
  `:scale`, ...). Returns the new globals."
  [m]
  (:globals (commit! #(update % :globals merge m))))

(defn tempo
  "Sets the tempo in BPM."
  [bpm]
  (globals! {:tempo bpm})
  bpm)

;; ---------------------------------------------------------------- sections & arrangement

(defn section!
  "Defines a section: the complete set of tracks it plays, as a map from tracks to nodes.
  `:base` names a section to build on, and nil removes a track inherited from it. nil instead
  of a map removes the section."
  [k tracks]
  (commit! #(session/put-section % k tracks))
  k)

(defn launch
  "Plays a section from the next bar: its tracks start from their first step and every other
  track stops."
  [section]
  (commit! #(session/launch-section % section))
  section)

(defn snap!
  "Saves the tracks playing now as a section."
  [section]
  (commit! #(session/snapshot % section))
  section)

(defn- next-bar [] (inc (or (live/current-bar) -1)))

(defn arrange!
  "Plays sections in order from the next bar. `plan` is a vector of `[section bars]` steps;
  `[section bars {:fill n}]` makes `:if :fill` steps play in the step's last n bars."
  [plan]
  (commit! #(assoc % :arrangement plan :arrangement-start (next-bar) :current-step nil))
  plan)

(defn fill
  "Makes steps with `:if :fill` play for the next `bars` bars (default 1)."
  ([] (fill 1))
  ([bars]
   (let [from (next-bar)]
     (commit! #(update % :fill (fn [f] (into (set (remove (fn [b] (< b from)) f))
                                             (range from (+ from bars))))))
     nil)))

;; ---------------------------------------------------------------- files & views

(defn save!
  "Writes the session as an EDN song file: globals, kits, instruments, definitions, sections,
  arrangement and tracks. Bundled parts and the audition track are left out."
  [path]
  (io/write-song! (update (session) :tracks dissoc :audition) path))

(defn load!
  "Replaces the session with a song: a file path, a classpath resource or the name of a
  bundled song (see `bb songs`). An arrangement in the song starts at the next bar."
  [path]
  (let [song (io/read-song path)
        start (next-bar)]
    (commit! (fn [_] (cond-> (io/song->session song)
                       (:arrangement song) (assoc :arrangement-start start))))
    path))

(defn render!
  "Renders `bars` bars of the session to a 48 kHz stereo WAV file, without an audio device."
  [path bars]
  (output/write-wav! (live/render (session) bars) path))

(defn show
  "Prints a text grid of a track, a definition or a node: one row per drum role or pitch,
  16 columns per bar."
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
  "Lists bundled parts, kits and instruments by namespace, or searches their names, tags and
  descriptions for `term`."
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

(defn audition
  "Plays a part on the `:audition` track, starting the transport (at the part's tempo) if it
  is stopped. nil stops the audition."
  [part]
  (if part
    (do (play :audition (if (keyword? part) [part] part))
        (when-not (live/current-bar)
          (some-> (library/about part) :tempo tempo)
          (start!))
        part)
    (clear :audition)))

(defn describe
  "Prints what `k` is: a part's data and grid, a kit's role map, or an instrument's
  parameters with their meaning."
  [k]
  (let [{:keys [defs kits instruments]} (library/catalog (session))
        about (library/about k)]
    (when about
      (println (str k "  " (:doc about)
                    (when (:tempo about) (str "  (" (:tempo about) " BPM)"))
                    (when (seq (:tags about)) (str "  " (str/join " " (sort (map name (:tags about)))))))))
    (cond
      (contains? defs k)
      (do (println (pr-str (defs k)))
          (show k))

      (or (= k :kit/default) (contains? kits k))
      (doseq [[role inst] (sort-by key (instruments/resolve-kit kits k))]
        (println (format "  %-4s %s" (name role) inst)))

      (or (contains? instruments k) (contains? instruments/builtin k))
      (doseq [[p v] (sort-by key (instruments/resolve-instrument instruments k))]
        (println (format "  %-12s %-10s %s" p (pr-str v) (instruments/param-docs p ""))))

      :else
      (throw (ex-info (str "Nothing named " k "; try (browse)") {:name k})))))
