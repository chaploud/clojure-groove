(ns chaploud.groove.query
  (:require [chaploud.groove.notation :as notation]))

(defn chance [& xs]
  (/ (double (bit-and (hash xs) 0xffffff)) 0x1000000))

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

(defn- apply-transform [[op & args] q len]
  (case op
    :rev (fn [lo hi iter]
           (->> (q 0 len iter)
                (map #(assoc % :t (- len (:t %) (:dur %))))
                (filter #(and (<= lo (:t %)) (< (:t %) hi)))))
    :fast (let [n (rationalize (first args))]
            (fn [lo hi iter]
              (for [[k start a b] (loop-windows len (* lo n) (* hi n))
                    ev (q a b (+ (* iter (ceil n)) k))]
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
               (remove #(< (chance :degrade iter (:t %)) (first args)) (q lo hi iter)))))

(defn- expand-chord [{:keys [event] :as ev}]
  (if-let [notes (:chord event)]
    (map #(assoc ev :event (merge (dissoc event :chord) %)) notes)
    [ev]))

(defn query [node lo hi iter]
  (case (:kind node)
    :steps (let [{:keys [step steps attrs]} node]
             (for [i (range (max 0 (ceil (/ lo step))) (min (count steps) (ceil (/ hi step))))
                   :let [s (steps i)]
                   :when s]
               {:t (* i step) :dur step :event (merge attrs s)}))
    :notes (for [{:keys [t dur event]} (:items node)
                 :when (and event (<= lo t) (< t hi))
                 ev (expand-chord {:t t :dur dur :event (merge (:attrs node) event)})]
             ev)
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
                        (fn [lo hi iter] (query child lo hi iter))
                        fx)]
          (q lo hi iter))))

(defn- passes? [{:keys [if prob]} {:keys [iter fill? seed]}]
  (and (case if
         nil true
         :fill fill?
         :!fill (not fill?)
         :1st (zero? iter)
         :!1st (pos? iter)
         (if (vector? if)
           (let [[a b] if] (= (mod iter b) (dec a)))
           (throw (ex-info (str "Unknown :if condition " (pr-str if)) {:if if}))))
       (or (nil? prob) (< (chance seed) prob))))

(defn- swung [t {:keys [swing step] :or {step 1/16}}]
  (let [pos (/ t step)]
    (if (and swing (integer? pos) (odd? pos))
      (+ t (* swing step))
      t)))

(defn- finish [ev ctx]
  (let [e (:event ev)
        t (cond-> (swung (:t ev) e)
            (:nudge e) (+ (* (:nudge e) (:dur ev))))
        r (long (:ratchet e 1))
        d (/ (:dur ev) r)]
    (for [i (range r)
          :when (passes? e (assoc ctx :seed [(:seed ctx) (:t ev) i]))]
      (assoc e :t (+ t (* i d)) :dur d))))

(defn bar-events [node bars-since-launch {:keys [fill? seed]}]
  (let [len (:len node)
        lo bars-since-launch]
    (sort-by :t
             (for [[k start a b] (loop-windows len lo (inc lo))
                   ev (query node a b k)
                   out (finish (update ev :t + (- start lo))
                               {:iter k :fill? fill? :seed [seed bars-since-launch]})]
               out))))
