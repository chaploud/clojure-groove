(ns ^:no-doc chaploud.groove.query
  (:require [chaploud.groove.notation :as notation]
            [chaploud.groove.pitch :as pitch]
            [chaploud.groove.signal :as signal]))

(defn- floor [x] (long (Math/floor (double x))))
(defn- ceil [x] (long (Math/ceil (double x))))

(declare query)

(defn- loop-windows [len lo hi]
  (for [k (range (floor (/ lo len)) (ceil (/ hi len)))
        :let [start (* k len)
              a (max lo start)
              b (min hi (+ start len))]
        :when (< a b)]
    [k start (- a start) (- b start)]))

(defn- shift [events dt]
  (map #(update % :t + dt) events))

(defn- arp-index [order n k seed]
  (case order
    :up (mod k n)
    :down (- n 1 (mod k n))
    :up-down (if (= n 1)
               0
               (let [period (- (* 2 n) 2)
                     p (mod k period)]
                 (if (< p n) p (- period p))))
    :random (long (* n (notation/chance :arp seed k)))))

(defn- apply-transform [[op & args] q len]
  (case op
    :rev (fn [lo hi iter]
           (->> (q 0 len iter)
                (map #(assoc % :t (- len (:t %) (:dur %))))
                (filter #(and (<= lo (:t %)) (< (:t %) hi)))))
    :fast (let [n (long (first args))]
            (fn [lo hi iter]
              (for [[k start a b] (loop-windows len (* lo n) (* hi n))
                    ev (q a b (+ (* iter n) k))]
                (-> ev (update :t #(/ (+ start %) n)) (update :dur / n)))))
    :slow (let [n (long (first args))]
            (fn [lo hi iter]
              (let [seg (* (mod iter n) len)]
                (for [ev (q (/ (+ seg lo) n) (/ (+ seg hi) n) (quot iter n))]
                  (-> ev (update :t #(- (* n %) seg)) (update :dur * n))))))
    :every (let [[n tf] args
                 transformed (apply-transform tf q len)]
             (fn [lo hi iter]
               (if (zero? (mod iter n))
                 (transformed lo hi iter)
                 (q lo hi iter))))
    :transpose (fn [lo hi iter]
                 (map #(update-in % [:event :transpose] (fnil + 0) (first args)) (q lo hi iter)))
    :degrade (fn [lo hi iter]
               (remove #(< (notation/chance :degrade iter (:t %)) (first args)) (q lo hi iter)))
    :arp (let [[order rate] args
               rate (or rate 1/16)]
           (fn [lo hi iter]
             (for [{:keys [t dur event] :as ev} (q 0 len iter)
                   :let [n (pitch/chord-size event)]
                   [k start] (if (> n 1)
                               (map vector (range) (range t (+ t dur) rate))
                               [[nil t]])
                   :when (and (<= lo start) (< start hi))]
               (if k
                 {:t start :dur (min rate (- (+ t dur) start))
                  :event (assoc event :arp-index (arp-index order n k [iter t]))}
                 ev))))))

(defn query [node lo hi iter]
  (case (:kind node)
    :steps (let [{:keys [step steps attrs]} node]
             (for [i (range (max 0 (ceil (/ lo step))) (min (count steps) (ceil (/ hi step))))
                   :let [s (steps i)]
                   :when s]
               {:t (* i step) :dur step :event (merge attrs s)}))
    :notes (for [{:keys [t dur event]} (:items node)
                 :when (and event (<= lo t) (< t hi))]
             {:t t :dur dur :event (merge (:attrs node) event)})
    :cycle (for [{:keys [t dur event]} (notation/mini-events (:ast node) (+ iter lo) (+ iter hi))]
             {:t (- t iter) :dur dur :event (merge (:attrs node) event)})
    :seq (loop [[c & cs] (:children node), offset (num 0), out []]
           (if-not c
             out
             (let [a (max lo offset)
                   b (min hi (+ offset (:len c)))]
               (recur cs (+ offset (:len c))
                      (if (< a b)
                        (into out (shift (query c (- a offset) (- b offset) iter) offset))
                        out)))))
    :par (mapcat #(let [b (min hi (:len %))]
                    (when (< lo b) (query % lo b iter)))
                 (:children node))
    :rep (let [{:keys [n child]} node
               len (:len child)]
           (for [[k start a b] (loop-windows len lo hi)
                 :when (< k n)
                 ev (query child a b (+ (* iter n) k))]
             (update ev :t + start)))
    :fx (let [{:keys [child fx len]} node
              q (reduce (fn [q tf] (apply-transform tf q len))
                        (partial query child)
                        fx)]
          (q lo hi iter))))

(defn- condition-holds? [condition {:keys [iter fill?]}]
  (case condition
    nil true
    :fill fill?
    :!fill (not fill?)
    :1st (zero? iter)
    :!1st (pos? iter)
    (if (and (vector? condition) (= 2 (count condition)) (every? pos-int? condition)
             (<= (first condition) (second condition)))
      (let [[a b] condition] (= (mod iter b) (dec a)))
      (throw (ex-info (str "Unknown :if condition " (pr-str condition)
                           "; use :fill :!fill :1st :!1st or [a b] with 1 <= a <= b")
                      {:if condition})))))

(defn- passes? [{condition :if prob :prob} {:keys [seed all?] :as ctx}]
  (let [holds (condition-holds? condition ctx)]
    (or all?
        (and holds (or (nil? prob) (< (notation/chance seed) prob))))))

(defn- swung [t {:keys [swing swing-step step]}]
  (let [step (or swing-step step 1/16)
        pos (/ t step)]
    (if (and swing (integer? pos) (odd? pos))
      (+ t (* swing step))
      t)))

(defn- finish [ev ctx]
  (let [e (signal/resolve-signals (:event ev) (+ (:since ctx) (:t ev)))
        h (:humanize e)
        t (cond-> (swung (:t ev) e)
            (:nudge e) (+ (* (:nudge e) (:dur ev)))
            h (+ (* (- (notation/chance :humanize-t (:seed ctx) (:t ev)) 0.5) 2 h 1/64)))
        e (cond-> e
            h (assoc :vel-scale (+ 1.0 (* (- (notation/chance :humanize-v (:seed ctx) (:t ev)) 0.5) 0.5 h))))
        r (:ratchet e 1)
        _ (when-not (pos-int? r)
            (throw (ex-info (str ":ratchet must be a positive integer, got " (pr-str r)) {:ratchet r})))
        d (/ (:dur ev) r)]
    (for [i (range r)
          :when (passes? e (assoc ctx :seed [(:seed ctx) (:t ev) i]))]
      (assoc e :t (+ t (* i d)) :dur d))))

(defn bar-events [node bars-since-launch {:keys [fill? seed all?]}]
  (let [len (:len node)
        lo bars-since-launch]
    (sort-by :t
             (for [[k start a b] (loop-windows len lo (inc lo))
                   ev (query node a b k)
                   out (finish (update ev :t + (- start lo))
                               {:iter k :fill? fill? :all? all? :since bars-since-launch :seed [seed bars-since-launch]})]
               out))))
