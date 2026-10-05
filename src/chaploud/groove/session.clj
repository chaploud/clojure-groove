(ns ^:no-doc chaploud.groove.session
  (:require [chaploud.groove.engine.voices :as voices]
            [chaploud.groove.expand :as expand]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.library :as library]
            [chaploud.groove.pitch :as pitch]
            [chaploud.groove.query :as query]))

(def empty-session
  {:globals {:tempo 120}
   :instruments {}
   :kits {}
   :defs {}
   :tracks {}
   :mute #{}
   :solo #{}
   :fill #{}
   :sections {}})

(defn- fail
  ([msg data] (throw (ex-info msg data)))
  ([msg data cause] (throw (ex-info msg data cause))))

(def ^:private time-constants #{:decay :fdecay :length :pitch-decay :drive})

(def ^:private sends #{:delay :reverb})

(defn- check-params! [p]
  (doseq [[k v] p
          :when (number? v)]
    (when-not (Double/isFinite (double v))
      (fail (str (pr-str k) " must be a finite number, got " v) {:param k}))
    (when (and (time-constants k) (not (pos? v)))
      (fail (str (pr-str k) " must be greater than 0, got " v) {:param k})))
  (doseq [k sends
          :let [v (get p k)]
          :when (contains? p k)]
    (when-not (and (number? v) (<= 0 v 1))
      (fail (str (pr-str k) " is a send level between 0 and 1, got " (pr-str v)) {:param k})))
  (when-not (or (nil? (:bus p)) (#{:drums :bass :synth} (:bus p)))
    (fail (str ":bus must be :drums, :bass or :synth, got " (pr-str (:bus p))) {:param :bus}))
  (when-not (or (nil? (:choke p)) (keyword? (:choke p)))
    (fail (str ":choke names a group with a keyword, got " (pr-str (:choke p))) {:param :choke}))
  (let [{:keys [vel gate]} p]
    (when-not (and (number? vel) (<= 0 vel 2))
      (fail (str ":vel must be a number between 0 and 2, got " (pr-str vel)) {:param :vel}))
    (when-not (and (number? gate) (pos? gate))
      (fail (str ":gate must be a positive number, got " (pr-str gate)) {:param :gate})))
  p)

(defn params [catalog event bar-seconds]
  (for [p (pitch/resolve-pitches (instruments/resolve-event catalog event))
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

(declare arrangement-step)

(defn- fill? [session bar]
  (or (contains? (:fill session) bar)
      (let [{:keys [start bars opts]} (arrangement-step session bar)]
        (boolean (and (:fill opts) (>= bar (- (+ start bars) (:fill opts))))))))

(defn track-events
  ([session compiled track bar] (track-events session compiled track bar {}))
  ([session compiled track bar opts]
   (let [{:keys [launch]} (get-in session [:tracks track])
         node (compiled track)
         since (- bar (or launch bar))]
     (when (and node (>= since 0))
       (for [e (query/bar-events node since (merge {:fill? (fill? session bar)
                                                    :seed [track bar]}
                                                   opts))]
         (assoc e :track track))))))

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

(defn- validate-arrangement! [{:keys [arrangement sections]}]
  (when arrangement
    (when-not (sequential? arrangement)
      (fail "An arrangement is a vector of [section bars] or [section bars {:fill n}] steps"
            {:arrangement arrangement}))
    (doseq [step arrangement]
      (let [[section bars opts :as v] (when (vector? step) step)]
        (when-not (and (<= 2 (count v) 3) (contains? sections section) (pos-int? bars)
                       (or (nil? opts) (map? opts)))
          (fail (str "Arrangement step " (pr-str step) " must be [known-section bars] or [known-section bars {:fill n}]")
                {:step step}))
        (when-let [unknown (seq (remove #{:fill} (keys opts)))]
          (fail (str "Arrangement step " (pr-str step) " has unknown options " (vec unknown)) {:step step}))
        (when-let [n (:fill opts)]
          (when-not (and (pos-int? n) (<= n bars))
            (fail (str "Arrangement step " (pr-str step) ": :fill is a number of bars between 1 and " bars)
                  {:step step})))))))

(declare launch-section)

(defn validate! [session]
  (let [t (tempo session)]
    (when-not (and (number? t) (<= 20 t 999))
      (fail (str ":tempo must be a number between 20 and 999, got " (pr-str t)) {:tempo t})))
  (validate-arrangement! session)
  (doseq [section (keys (:sections session))]
    (try (validate-tracks! (launch-section session section))
         (catch clojure.lang.ExceptionInfo e
           (fail (str "Section " section ": " (ex-message e)) (assoc (ex-data e) :section section) e))))
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

(defn resolve-section [sections k]
  (loop [k k, seen #{}, acc {}]
    (cond
      (nil? k) (into {} (remove (comp nil? val)) acc)
      (seen k) (fail (str "Circular section :base through " k) {:section k})
      :else (let [m (or (get sections k) (fail (str "Unknown section " k) {:section k}))]
              (when-not (map? m)
                (fail (str "Section " k " must be a map of tracks to nodes") {:section k}))
              (doseq [t (keys (dissoc m :base))] (track-key! t))
              (recur (:base m) (conj seen k) (merge (dissoc m :base) acc))))))

(defn put-section [session k tracks]
  (if (nil? tracks)
    (update session :sections dissoc k)
    (do (resolve-section (assoc (:sections session) k tracks) k)
        (assoc-in session [:sections k] tracks))))

(defn launch-section [session section]
  (assoc session :tracks (update-vals (resolve-section (:sections session) section) (fn [node] {:node node}))))

(defn snapshot [session section]
  (assoc-in session [:sections section] (dissoc (track-nodes session) :audition)))

(defn arrangement-step [{:keys [arrangement arrangement-start]} bar]
  (when (and arrangement arrangement-start (>= bar arrangement-start))
    (loop [[[section bars opts] & more] arrangement, at arrangement-start, i 0]
      (cond
        (nil? section) nil
        (< bar (+ at bars)) {:index i :section section :start at :bars bars :opts opts}
        :else (recur more (+ at bars) (inc i))))))

(defn arrangement-end [{:keys [arrangement arrangement-start]}]
  (when (and arrangement arrangement-start)
    (+ arrangement-start (reduce + (map second arrangement)))))

(defn arrange [session plan start]
  (assoc session :arrangement plan :arrangement-start start :current-step nil))

(defn begin-bar [session bar]
  (let [{:keys [index section]} (arrangement-step session bar)
        end (arrangement-end session)
        session (cond
                  (and index (not= index (:current-step session)))
                  (assoc (launch-section session section) :current-step index)

                  (and end (= bar end))
                  (assoc session :tracks {} :current-step nil)

                  :else session)]
    (update session :tracks update-vals #(update % :launch (fn [l] (or l bar))))))

(defn rewind [session]
  (-> session
      (update :tracks update-vals #(dissoc % :launch))
      (assoc :fill #{} :current-step nil)
      (cond-> (:arrangement session) (assoc :arrangement-start 0))))
