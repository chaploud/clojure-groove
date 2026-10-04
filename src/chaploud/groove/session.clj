(ns chaploud.groove.session
  (:require [chaploud.groove.engine.voices :as voices]
            [chaploud.groove.expand :as expand]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.library :as library]
            [chaploud.groove.pitch :as pitch]
            [chaploud.groove.query :as query]
            [chaploud.groove.signal :as signal]))

(def empty-session
  {:globals {:tempo 120}
   :instruments {}
   :kits {}
   :defs {}
   :scenes {}
   :tracks {}
   :mute #{}
   :solo #{}
   :fill #{}})

(defn- fail
  ([msg data] (throw (ex-info msg data)))
  ([msg data cause] (throw (ex-info msg data cause))))

(def ^:private time-constants #{:decay :fdecay :length :pitch-decay :drive})

(defn- check-params! [p]
  (doseq [[k v] p
          :when (number? v)]
    (when-not (Double/isFinite (double v))
      (fail (str (pr-str k) " must be a finite number, got " v) {:param k}))
    (when (and (time-constants k) (not (pos? v)))
      (fail (str (pr-str k) " must be greater than 0, got " v) {:param k})))
  (let [{:keys [vel gate]} p]
    (when-not (and (number? vel) (<= 0 vel 2))
      (fail (str ":vel must be a number between 0 and 2, got " (pr-str vel)) {:param :vel}))
    (when-not (and (number? gate) (pos? gate))
      (fail (str ":gate must be a positive number, got " (pr-str gate)) {:param :gate})))
  p)

(defn params [catalog event bar-seconds]
  (for [p (pitch/resolve-pitches (signal/resolve-signals (instruments/resolve-event catalog event)
                                                         (:pos event (:t event))))
        :let [p (check-params! (merge {:vel 0.8 :gate 1} p))]]
    (do
      (when (and (= :synth (:voice p)) (nil? (:midi p)))
        (fail (str "Synth event for " (:inst event) " has no pitch") {:event event}))
      (assoc p
             :vel (* (double (:vel p)) (double (:vel-scale p 1.0)))
             :dur-s (* (double (:dur p)) (double (:gate p)) bar-seconds)))))

(defn tempo [session]
  (get-in session [:globals :tempo] 120))

(defn bar-seconds [session]
  (/ (* 4 60.0) (double (tempo session))))

(defn track-nodes [session]
  (update-vals (:tracks session) :node))

(defn compile-tracks [{:keys [tracks globals] :as session}]
  (let [defs (:defs (library/catalog session))]
    (into {}
          (for [[track {:keys [node]}] tracks]
            [track (try (expand/expand node defs globals [track])
                        (catch clojure.lang.ExceptionInfo e
                          (fail (str "Track " track ": " (ex-message e))
                                (assoc (ex-data e) :track track))))]))))

(defn- audible? [{:keys [mute solo]} track]
  (and (not (mute track))
       (or (empty? solo) (contains? solo track))))

(defn track-events
  ([session compiled track bar] (track-events session compiled track bar {}))
  ([session compiled track bar opts]
   (let [{:keys [launch]} (get-in session [:tracks track])
         node (compiled track)
         since (- bar (or launch bar))]
     (when (and node (>= since 0))
       (for [e (query/bar-events node since (merge {:fill? (contains? (:fill session) bar)
                                                    :seed [track bar]}
                                                   opts))]
         (assoc e :track track :pos (+ since (:t e))))))))

(defn bar-events [session compiled bar]
  (let [secs (bar-seconds session)]
    (for [track (sort (keys (:tracks session)))
          :when (audible? session track)
          e (track-events session compiled track bar)
          p (params (library/catalog session) e secs)]
      p)))

(defn- probe-bars [node]
  (min 64 (max 8 (* 4 (long (Math/ceil (double (:len node))))))))

(defn- probe-track! [session compiled track]
  (let [secs (bar-seconds session)
        catalog (library/catalog session)
        session (assoc-in session [:tracks track :launch] 0)
        voice-params (volatile! #{})]
    (doseq [bar (range (probe-bars (compiled track)))
            e (track-events session compiled track bar {:all? true})
            p (params catalog e secs)]
      (vswap! voice-params conj (dissoc p :t :dur :dur-s :track)))
    (doseq [p @voice-params]
      (voices/make-voice (assoc p :dur-s 0.1) 48000 0))))

(defn- validate-tracks! [session]
  (let [compiled (compile-tracks session)]
    (doseq [track (keys compiled)]
      (try (probe-track! session compiled track)
           (catch Exception e
             (fail (str "Track " track ": " (ex-message e))
                   (assoc (ex-data e) :track track)
                   e))))
    compiled))

(declare launch-scene)

(defn- validate-arrangement! [{:keys [arrangement scenes]}]
  (when arrangement
    (when-not (sequential? arrangement)
      (fail "An arrangement is a vector of [scene bars] pairs" {:arrangement arrangement}))
    (doseq [step arrangement]
      (let [[scene bars] (when (vector? step) step)]
        (when-not (and (contains? scenes scene) (pos-int? bars))
          (fail (str "Arrangement step " (pr-str step) " must be [known-scene positive-bars]")
                {:step step}))))))

(defn validate! [session]
  (let [t (tempo session)]
    (when-not (and (number? t) (<= 20 t 999))
      (fail (str ":tempo must be a number between 20 and 999, got " (pr-str t)) {:tempo t})))
  (validate-arrangement! session)
  (doseq [scene (keys (:scenes session))]
    (try (validate-tracks! (launch-scene session scene))
         (catch clojure.lang.ExceptionInfo e
           (fail (str "Scene " scene ": " (ex-message e)) (assoc (ex-data e) :scene scene) e))))
  (validate-tracks! session))

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
    (assoc-in session [:tracks track :node] node)))

(defn put-def [session k node]
  (def-key! k)
  (if (nil? node)
    (update session :defs dissoc k)
    (let [s (assoc-in session [:defs k] node)]
      (try (expand/expand k (:defs (library/catalog s)))
           (catch clojure.lang.ExceptionInfo e
             (when (:cycle (ex-data e))
               (fail (str (ex-message e)
                          (when (contains? (:defs @library/library) k)
                            "; a bundled part cannot be extended under its own name, use a new one"))
                     (ex-data e)))))
      s)))

(defn put-kit [session k roles]
  (def-key! k)
  (cond
    (nil? roles) (update session :kits dissoc k)
    (map? roles) (assoc-in session [:kits k] roles)
    :else (fail (str "A kit is a map of roles to instruments, got " (pr-str roles)) {:kit k})))

(defn put-instrument [session k params]
  (def-key! k)
  (assoc-in session [:instruments k] params))

(defn launch-scene [session scene]
  (let [tracks (or (get-in session [:scenes scene])
                   (fail (str "Unknown scene " scene) {:scene scene}))]
    (reduce-kv (fn [s track node]
                 (track-key! track)
                 (if node
                   (assoc-in s [:tracks track] {:node node})
                   (update s :tracks dissoc track)))
               (assoc session :current-scene scene)
               tracks)))

(defn snapshot [session scene]
  (assoc-in session [:scenes scene] (track-nodes session)))

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
    (update session :tracks update-vals #(update % :launch (fn [l] (or l bar))))))

(defn rewind [session]
  (-> session
      (update :tracks update-vals #(dissoc % :launch))
      (assoc :fill #{} :current-scene nil)
      (cond-> (:arrangement session) (assoc :arrangement-start 0))))
