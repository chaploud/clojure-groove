(ns chaploud.groove.signal)

(defn signal? [v]
  (and (vector? v) (#{:lfo :ramp} (first v))))

(defn- fail [sig msg]
  (throw (ex-info (str msg ", got " (pr-str sig)) {:signal sig})))

(defn value [[kind & args :as sig] pos]
  (case kind
    :lfo (let [[shape period lo hi] args]
           (when-not (and (number? period) (pos? period) (number? lo) (number? hi))
             (fail sig "[:lfo shape period-bars low high] needs a positive period and numeric bounds"))
           (let [phase (mod (/ (double pos) period) 1.0)
                 u (case shape
                     :sine (- 0.5 (* 0.5 (Math/cos (* 2 Math/PI phase))))
                     :tri (- 1.0 (Math/abs (- (* 2.0 phase) 1.0)))
                     :saw phase
                     :square (if (< phase 0.5) 0.0 1.0)
                     (fail sig "LFO shape must be :sine :tri :saw or :square"))]
             (+ lo (* u (- hi lo)))))
    :ramp (let [[from to bars] args]
            (when-not (and (number? from) (number? to) (number? bars) (pos? bars))
              (fail sig "[:ramp from to bars] needs numbers and a positive length"))
            (+ from (* (- to from) (mod (/ (double pos) bars) 1.0))))))

(defn resolve-signals [event pos]
  (reduce-kv (fn [m k v] (if (signal? v) (assoc m k (double (value v pos))) m))
             event
             event))
