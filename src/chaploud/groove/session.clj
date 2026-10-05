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

(def ^:private time-constants #{:decay :fdecay :length :pitch-decay :drive :glide-time})

(def ^:private sends #{:delay :reverb})

(def ^:private numeric-params
  (set (remove #{:voice :bus :duck :choke :osc} (keys instruments/param-docs))))

(defn- check-params! [p]
  (doseq [k (instruments/required-params (:voice p))
          :when (not (contains? p k))]
    (fail (str (:inst p) " needs " (pr-str k) " for its " (pr-str (:voice p)) " voice; build on a built-in with :base")
          {:param k}))
  (doseq [k numeric-params
          :let [v (get p k)]
          :when (and (contains? p k) (not (number? v)))]
    (fail (str (pr-str k) " must be a number, got " (pr-str v)) {:param k}))
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

(def mix-globals
  {:tempo [20 999 120]
   :delay-feedback [0 0.95 0.38]
   :duck-depth [0 1 0.75]})

(defn global [session k]
  (get-in session [:globals k] (peek (mix-globals k))))

(defn mix-settings [session]
  (into {} (for [k (keys mix-globals)] [k (global session k)])))

(defn tempo [session] (global session :tempo))

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
  (let [secs (bar-seconds session)
        catalog (library/catalog session)]
    (for [track (sort (keys (:tracks session)))
          :when (audible? session track)
          e (track-events session compiled track bar)
          p (params catalog e secs)]
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

(declare launch-section track-key!)

(def ^:private instrument-keys (assoc instruments/param-docs :base "" :vel "" :transpose ""))

(defn- validate-instruments! [{:keys [instruments kits] :as session}]
  (let [catalog (library/catalog session)]
    (doseq [k (keys kits)
            [role inst] (instruments/resolve-kit (:kits catalog) k)]
      (try (instruments/resolve-instrument (:instruments catalog) inst)
           (catch clojure.lang.ExceptionInfo e
             (fail (str "Kit " k " role " role ": " (ex-message e)) {:kit k :role role}))))
    (doseq [k (keys instruments)] (instruments/resolve-instrument (:instruments catalog) k)))
  (doseq [[k params] instruments]
    (when-not (map? params)
      (fail (str "Instrument " k " must be a map of parameters, got " (pr-str params)) {:instrument k}))
    (doseq [p (keys params)
            :when (not (contains? instrument-keys p))]
      (fail (str "Instrument " k " has no parameter " p (expand/suggestion p instrument-keys)
                 "; (describe :synth/acid) lists them")
            {:instrument k :param p}))))

(defn validate! [session]
  (doseq [[k [lo hi]] mix-globals
          :let [v (global session k)]]
    (when-not (and (number? v) (<= lo v hi))
      (fail (str (pr-str k) " must be a number between " lo " and " hi ", got " (pr-str v)) {k v})))
  (doseq [t (keys (:tracks session))] (track-key! t))
  (validate-instruments! session)
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
  (cond
    (nil? params) (update session :instruments dissoc k)
    (map? params) (assoc-in session [:instruments k] params)
    :else (fail (str "An instrument is a map of parameters, got " (pr-str params)) {:instrument k})))

(defn resolve-section [sections k]
  (let [tracks (instruments/resolve-with-base sections {} k :section)]
    (doseq [t (keys tracks)] (track-key! t))
    (into {} (remove (comp nil? val)) tracks)))

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

(defn arrangement-bars [{:keys [arrangement]}]
  (when (seq arrangement)
    (reduce + (map second arrangement))))

(defn arrangement-end [{:keys [arrangement-start] :as session}]
  (when-let [bars (and arrangement-start (arrangement-bars session))]
    (+ arrangement-start bars)))

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
