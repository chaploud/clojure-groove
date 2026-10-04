(ns chaploud.groove.session
  (:require [chaploud.groove.expand :as expand]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.pitch :as pitch]
            [chaploud.groove.query :as query]))

(def empty-session
  {:globals {:tempo 120}
   :instruments {}
   :defs {}
   :scenes {}
   :tracks {}
   :mute #{}
   :solo #{}
   :fill #{}})

(defn- fail [msg data] (throw (ex-info msg data)))

(defn params [instrument-defs event bar-seconds]
  (let [inst (or (:inst event) (fail "Event has no :inst" {:event event}))
        p (pitch/resolve-midi (merge (instruments/resolve-instrument instrument-defs inst) event))]
    (when (and (= :synth (:voice p)) (nil? (:midi p)))
      (fail (str "Synth event for " inst " has no pitch") {:event event}))
    (assoc p
           :vel (double (:vel p 0.8))
           :dur-s (* (double (:dur p)) (double (:gate p 1)) bar-seconds))))

(defn bar-seconds [session]
  (/ (* 4 60.0) (double (get-in session [:globals :tempo] 120))))

(defn compile-tracks [{:keys [tracks defs globals]}]
  (into {}
        (for [[track {:keys [node]}] tracks]
          [track (try (expand/expand node defs globals {} [track] #{})
                      (catch clojure.lang.ExceptionInfo e
                        (fail (str "Track " track ": " (ex-message e))
                              (assoc (ex-data e) :track track))))])))

(defn- audible? [{:keys [mute solo]} track]
  (and (not (mute track))
       (or (empty? solo) (contains? solo track))))

(defn track-events [session compiled track bar]
  (let [{:keys [launch]} (get-in session [:tracks track])
        node (compiled track)
        since (- bar (or launch bar))]
    (when (and node (>= since 0))
      (for [e (query/bar-events node since {:fill? (contains? (:fill session) bar)
                                            :seed [track bar]})]
        (assoc e :track track)))))

(defn bar-events [session compiled bar]
  (let [secs (bar-seconds session)]
    (for [track (sort (keys (:tracks session)))
          :when (audible? session track)
          e (track-events session compiled track bar)]
      (params (:instruments session) e secs))))

(defn validate! [session]
  (let [compiled (compile-tracks session)
        probe (update session :tracks update-vals #(assoc % :launch 0))]
    (doseq [[track node] compiled
            bar (range (min 8 (long (Math/ceil (double (:len node))))))]
      (try (doall (bar-events (assoc probe :mute #{} :solo #{track}) compiled bar))
           (catch clojure.lang.ExceptionInfo e
             (fail (str "Track " track ": " (ex-message e)) (assoc (ex-data e) :track track)))))
    compiled))

;; ---------------------------------------------------------------- transitions

(defn- track-key! [k]
  (when-not (simple-keyword? k)
    (fail (str "Track names are simple keywords like :kick, got " (pr-str k)) {:track k})))

(defn- def-key! [k]
  (when-not (qualified-keyword? k)
    (fail (str "Definition names are qualified keywords like :clip/bass, got " (pr-str k)) {:key k})))

(defn play [session track node]
  (track-key! track)
  (if (nil? node)
    (update session :tracks dissoc track)
    (update-in session [:tracks track] #(assoc % :node node))))

(defn put-def [session k node]
  (def-key! k)
  (if (nil? node)
    (update session :defs dissoc k)
    (assoc-in session [:defs k] node)))

(defn put-instrument [session k params]
  (def-key! k)
  (assoc-in session [:instruments k] params))

(defn launch-scene [session scene]
  (let [tracks (or (get-in session [:scenes scene])
                   (fail (str "Unknown scene " scene) {:scene scene}))]
    (reduce-kv (fn [s track node]
                 (if node
                   (assoc-in s [:tracks track] {:node node})
                   (update s :tracks dissoc track)))
               (assoc session :current-scene scene)
               tracks)))

(defn snapshot [session scene]
  (assoc-in session [:scenes scene] (update-vals (:tracks session) :node)))

(defn arrangement-scene [{:keys [arrangement arrangement-start]} bar]
  (when (and arrangement arrangement-start (>= bar arrangement-start))
    (loop [[[scene bars] & more] arrangement, at arrangement-start]
      (cond
        (nil? scene) nil
        (< bar (+ at bars)) scene
        :else (recur more (+ at bars))))))

(defn begin-bar [session bar]
  (let [scene (arrangement-scene session bar)
        session (if (and scene (not= scene (:current-scene session)))
                  (launch-scene session scene)
                  session)]
    (update session :tracks update-vals #(update % :launch (fnil identity bar)))))
