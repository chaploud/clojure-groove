(ns chaploud.groove.notation
  (:require [chaploud.groove.pitch :as pitch]
            [clojure.string :as str]))

(defn- fail [msg data] (throw (ex-info msg data)))

;; ---------------------------------------------------------------- steps

(def ^:private step-rests #{nil false 0 :_ :- '_ "~"})

(defn- step-char [ch i]
  (case ch
    (\x \o) {}
    \X {:vel 1.0}
    (\. \- \_ \~) nil
    (fail (str "Unexpected character " (pr-str ch) " at column " (inc i) " of step string")
          {:char ch :column (inc i)})))

(defn parse-steps [x]
  (cond
    (string? x) (into []
                      (comp (map-indexed vector)
                            (remove (fn [[_ ch]] (or (Character/isWhitespace (char ch)) (= ch \|))))
                            (map (fn [[i ch]] (step-char ch i))))
                      x)
    (vector? x) (mapv (fn [v]
                        (cond
                          (contains? step-rests v) nil
                          (map? v) v
                          (#{:x :o true 1} v) {}
                          (= :X v) {:vel 1.0}
                          (and (number? v) (< 0 v 1)) {:vel (double v)}
                          :else (fail (str "Invalid step: " (pr-str v)) {:step v})))
                      x)
    :else (fail "Steps must be a string or a vector" {:steps x})))

(def ^:private note-rests #{nil :_ '_ "~"})

;; ---------------------------------------------------------------- notes

(def ^:private durations {:w 1 :h 1/2 :q 1/4 :e 1/8 :s 1/16 :t 1/32})

(defn- duration-of [k]
  (let [[_ base mod] (re-matches #"([whqest])(\.|3)?" (name k))]
    (when-let [d (and base (durations (keyword base)))]
      (case mod "." (* d 3/2) "3" (* d 2/3) d))))

(defn- pitch-of [v]
  (cond
    (integer? v) {:degree v}
    (and (keyword? v) (pitch/parse-note v)) {:note v}
    (set? v) {:chord (mapv pitch-of v)}
    (map? v) v
    :else nil))

(defn parse-notes [items default-dur]
  (when-not (vector? items) (fail "Notes must be a vector" {:notes items}))
  (loop [[x & more :as xs] items, dur default-dur, t (num 0), out []]
    (cond
      (empty? xs) out
      (ratio? x) (recur more x t out)
      (and (keyword? x) (duration-of x)) (recur more (duration-of x) t out)
      (contains? note-rests x) (recur more dur (+ t dur) (conj out {:t t :dur dur}))
      :else (let [ev (or (pitch-of x) (fail (str "Invalid note: " (pr-str x)) {:note x}))
                  d (or (:dur ev) dur)]
              (recur more dur (+ t d) (conj out {:t t :dur d :event (dissoc ev :dur)}))))))

;; ---------------------------------------------------------------- mini-notation

(def sound-aliases
  {"bd" :drum/kick "kick" :drum/kick "sd" :drum/snare "snare" :drum/snare
   "cp" :drum/clap "clap" :drum/clap "hh" :drum/hat "ch" :drum/hat "oh" :drum/open-hat
   "rim" :drum/rim "rs" :drum/rim "lt" :drum/tom-low "mt" :drum/tom-mid "ht" :drum/tom-high
   "cb" :drum/cowbell "cr" :drum/crash "rd" :drum/ride})

(defn- word->event [w]
  (let [[base variant] (str/split w #":" 2)]
    (cond-> (cond
              (re-matches #"-?\d+" base) {:degree (parse-long base)}
              (sound-aliases base) {:inst (sound-aliases base)}
              (pitch/parse-note base) {:note (keyword base)}
              :else (fail (str "Unknown word in pattern: " base) {:word base}))
      variant (assoc :variant (parse-long variant)))))

(defn- tokenize [s]
  (re-seq #"[\[\]<>,()*/!?@~]|-?\d+(?:\.\d+)?(?::\d+)?|[A-Za-z#][\w#:.]*|\S" s))

(defn- number-token [t]
  (or (some-> t parse-double rationalize) (fail (str "Expected a number, got " (pr-str t)) {:token t})))

(declare parse-sequence)

(defn- parse-atom [[t & more]]
  (case t
    "[" (let [[node rest] (parse-sequence more "]")] [node rest])
    "<" (let [[node rest] (parse-sequence more ">")]
          [(if (= :stack (first node))
             (fail "',' is not supported inside < >" {})
             [:alt (mapv second (second node))])
           rest])
    ("~" "-") [[:rest] more]
    (nil "]" ">" "," ")") (fail (str "Unexpected " (or t "end of pattern")) {:token t})
    (if (re-matches #"[\[\]<>()*/!?@]" t)
      (fail (str "Unexpected " t) {:token t})
      [[:word (word->event t)] more])))

(defn- parse-term [toks]
  (let [[node toks] (parse-atom toks)]
    (loop [node node, toks toks, weight (num 1), copies (num 1)]
      (let [[t n & more] toks
            num? (boolean (and n (re-matches #"\d+(?:\.\d+)?|\.\d+" n)))]
        (case t
          "*" (recur [:fast (number-token n) node] more weight copies)
          "/" (recur [:fast (/ 1 (number-token n)) node] more weight copies)
          "@" (recur node more (number-token n) copies)
          "?" (if num?
                (recur [:degrade (number-token n) node] more weight copies)
                (recur [:degrade 0.5 node] (rest toks) weight copies))
          "!" (if (and num? (re-matches #"\d+" n))
                (recur node more weight (* copies (parse-long n)))
                (recur node (rest toks) weight (inc copies)))
          "(" (let [[k _ s & more2] (rest toks)
                    [rot more3] (if (= "," (first more2)) [(second more2) (nnext more2)] ["0" more2])]
                (when-not (= ")" (first more3))
                  (fail "Expected ) after euclid arguments" {}))
                (recur [:euclid (long (number-token k)) (long (number-token s)) (long (number-token rot)) node]
                       (rest more3) weight copies))
          [(repeat copies [weight node]) toks])))))

(defn- parse-sequence [toks close]
  (loop [toks toks, layer [], layers []]
    (let [t (first toks)]
      (cond
        (= t close) (let [layers (conj layers layer)]
                      [(if (= 1 (count layers))
                         [:seq (first layers)]
                         [:stack (mapv #(vector :seq %) layers)])
                       (rest toks)])
        (nil? t) (fail (str "Missing " close) {})
        (= t ",") (recur (rest toks) [] (conj layers layer))
        :else (let [[terms rest-toks] (parse-term toks)]
                (recur rest-toks (into layer terms) layers))))))

(defn parse-mini [s]
  (when-not (string? s) (fail "A cycle pattern must be a string" {:pattern s}))
  (first (parse-sequence (tokenize s) nil)))

(defn euclid
  ([k n] (euclid k n 0))
  ([k n rotation]
   (mapv #(< (mod (* (+ % rotation) k) n) k) (range n))))

(defn- chance [& xs]
  (/ (double (bit-and (hash xs) 0xffffff)) 0x1000000))

(defn- query-mini [node lo hi]
  (case (first node)
    :rest []
    :word (for [k (range (long (Math/ceil (double lo))) (long (Math/ceil (double hi))))]
            {:t k :dur 1 :event (second node)})
    :seq (let [terms (second node)
               total (reduce + (map first terms))]
           (when (pos? total)
             (for [k (range (long (Math/floor (double lo))) (long (Math/ceil (double hi))))
                   :let [starts (reductions + 0 (map first terms))]
                   [[w child] start] (map vector terms starts)
                   :let [s (+ k (/ start total))
                         e (+ s (/ w total))
                         a (max lo s)
                         b (min hi e)]
                   :when (< a b)
                   :let [scale (/ total w)
                         to-inner #(+ k (* (- % s) scale))]
                   ev (query-mini child (to-inner a) (to-inner b))]
               (-> ev
                   (update :t #(+ s (/ (- % k) scale)))
                   (update :dur #(/ % scale))))))
    :stack (mapcat #(query-mini % lo hi) (second node))
    :alt (let [children (second node)
               n (count children)]
           (for [k (range (long (Math/floor (double lo))) (long (Math/ceil (double hi))))
                 :let [a (max lo k) b (min hi (inc k))
                       inner (Math/floorDiv (long k) (long n))]
                 :when (< a b)
                 ev (query-mini (children (Math/floorMod (long k) (long n)))
                                (+ inner (- a k)) (+ inner (- b k)))]
             (update ev :t #(+ k (- % inner)))))
    :fast (let [[_ f child] node
                f (rationalize f)]
            (when (pos? f)
              (for [ev (query-mini child (* lo f) (* hi f))]
                (-> ev (update :t / f) (update :dur / f)))))
    :degrade (let [[_ p child] node]
               (remove #(< (chance :degrade (:t %)) p) (query-mini child lo hi)))
    :euclid (let [[_ k n rot child] node
                  pattern (euclid k n rot)]
              (query-mini [:seq (mapv #(vector 1 (if % child [:rest])) pattern)] lo hi))))

(defn mini-events [ast lo hi]
  (sort-by :t (query-mini ast (rationalize lo) (rationalize hi))))
