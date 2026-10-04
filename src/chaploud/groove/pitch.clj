(ns chaploud.groove.pitch
  (:require [clojure.string :as str]))

(def scales
  {:major [0 2 4 5 7 9 11]
   :minor [0 2 3 5 7 8 10]
   :dorian [0 2 3 5 7 9 10]
   :phrygian [0 1 3 5 7 8 10]
   :lydian [0 2 4 6 7 9 11]
   :mixolydian [0 2 4 5 7 9 10]
   :locrian [0 1 3 5 6 8 10]
   :harmonic-minor [0 2 3 5 7 8 11]
   :melodic-minor [0 2 3 5 7 9 11]
   :major-pentatonic [0 2 4 7 9]
   :minor-pentatonic [0 3 5 7 10]
   :blues [0 3 5 6 7 10]
   :chromatic [0 1 2 3 4 5 6 7 8 9 10 11]})

(def ^:private letter->pc {"c" 0 "d" 2 "e" 4 "f" 5 "g" 7 "a" 9 "b" 11})

(defn parse-note [x]
  (when (or (keyword? x) (string? x) (symbol? x))
    (when-let [[_ letter acc octave] (re-matches #"(?i)([a-g])(#|s|b)?(-?\d+)?" (name x))]
      {:pc (mod (+ (letter->pc (str/lower-case letter))
                   (case acc ("#" "s") 1 "b" -1 0))
                12)
       :octave (some-> octave parse-long)})))

(defn note->midi [x default-octave]
  (let [{:keys [pc octave]} (or (parse-note x)
                                (throw (ex-info (str "Not a note name: " (pr-str x)) {:note x})))]
    (+ pc (* 12 (inc (or octave default-octave))))))

(defn- scale-steps [scale]
  (cond
    (keyword? scale) (or (scales scale)
                         (throw (ex-info (str "Unknown scale: " scale) {:scale scale :known (keys scales)})))
    (and (sequential? scale) (seq scale)) (vec scale)
    :else (throw (ex-info (str "Invalid scale: " (pr-str scale)) {:scale scale}))))

(defn degree->midi [degree {:keys [root scale octave] :or {root :c scale :major octave 4}}]
  (let [steps (scale-steps scale)
        n (count steps)
        d (long degree)]
    (+ (:pc (or (parse-note root) (throw (ex-info (str "Invalid root: " (pr-str root)) {:root root}))))
       (steps (Math/floorMod d n))
       (* 12 (+ 1 octave (Math/floorDiv d n))))))

(defn resolve-midi [{:keys [midi note degree transpose octave] :or {octave 4} :as event}]
  (let [m (cond
            midi midi
            note (note->midi note octave)
            degree (degree->midi degree event))]
    (cond-> event
      m (assoc :midi (+ m (or transpose 0))))))

(defn midi->hz ^double [midi]
  (* 440.0 (Math/pow 2.0 (/ (- (double midi) 69.0) 12.0))))

;; ---------------------------------------------------------------- chords

(def ^:private numerals {"i" 0 "ii" 1 "iii" 2 "iv" 3 "v" 4 "vi" 5 "vii" 6})

(def ^:private qualities
  {"" [[0 4 7] [0 3 7]]
   "7" [[0 4 7 10] [0 3 7 10]]
   "maj7" [[0 4 7 11] [0 3 7 11]]
   "6" [[0 4 7 9] [0 3 7 9]]
   "9" [[0 4 7 10 14] [0 3 7 10 14]]
   "add9" [[0 4 7 14] [0 3 7 14]]
   "sus2" [[0 2 7] [0 2 7]]
   "sus4" [[0 5 7] [0 5 7]]
   "dim" [[0 3 6] [0 3 6]]
   "dim7" [[0 3 6 9] [0 3 6 9]]
   "m7b5" [[0 3 6 10] [0 3 6 10]]
   "aug" [[0 4 8] [0 4 8]]})

(defn parse-roman [x]
  (when (or (keyword? x) (string? x))
    (when-let [[_ acc numeral suffix] (re-matches #"([b#]?)(VII|VI|V|IV|III|II|I|vii|vi|v|iv|iii|ii|i)(.*)" (name x))]
      (when-let [[major minor] (qualities suffix)]
        {:degree (numerals (.toLowerCase ^String numeral))
         :shift ({"b" -1 "#" 1} acc 0)
         :intervals (if (Character/isUpperCase (.charAt ^String numeral 0)) major minor)}))))

(defn chord-size [{:keys [roman chord]}]
  (cond
    roman (count (:intervals (parse-roman roman)))
    chord (count chord)
    :else 1))

(defn- voice [midis {:keys [voicing inv]}]
  (let [sorted (vec (sort midis))
        sorted (reduce (fn [ms _] (vec (sort (conj (subvec ms 1) (+ 12 (first ms))))))
                       sorted
                       (range (or inv 0)))
        n (count sorted)]
    (case (or voicing :close)
      :close sorted
      :root [(first sorted)]
      :open (if (>= n 3) (vec (sort (update sorted 1 + 12))) sorted)
      :drop2 (if (>= n 3) (vec (sort (update sorted (- n 2) - 12))) sorted)
      (throw (ex-info (str "Unknown :voicing " (pr-str voicing) "; use :close :open :drop2 or :root")
                      {:voicing voicing})))))

(defn- glide-source [{:keys [glide-from] :as event}]
  (if glide-from
    (let [from (resolve-midi (merge (apply dissoc event :glide-from :midi :note :degree :roman :chord [])
                                    glide-from))]
      (assoc (dissoc event :glide-from) :glide-from-midi (:midi from)))
    event))

(defn resolve-pitches [{:keys [roman chord arp-index transpose] :as event}]
  (if-let [midis (cond
                   roman (let [{:keys [degree shift intervals]}
                               (or (parse-roman roman)
                                   (throw (ex-info (str "Not a chord symbol: " (pr-str roman)) {:roman roman})))
                               base (+ (degree->midi degree (cond-> event (not (zero? shift)) (assoc :scale :major)))
                                       shift (or transpose 0))]
                           (map #(+ base %) intervals))
                   chord (map #(:midi (resolve-midi (merge event %))) chord))]
    (let [voiced (voice midis event)
          picked (if arp-index [(voiced (mod arp-index (count voiced)))] voiced)]
      (for [m picked]
        (glide-source (assoc (dissoc event :roman :chord :arp-index) :midi m))))
    [(glide-source (resolve-midi event))]))
