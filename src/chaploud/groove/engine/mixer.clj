(ns chaploud.groove.engine.mixer
  (:require [chaploud.groove.engine.voices :as voices])
  (:import [chaploud.groove.engine.voices Voice]
           [java.util ArrayList Iterator PriorityQueue]
           [java.util.concurrent ConcurrentLinkedQueue]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:const block 256)

(deftype Scheduled [^long frame ^long bus ^boolean duck ^Voice voice])

(deftype Active [^long bus ^Voice voice ^longs from])

(defn- bus-index ^long [k]
  (case k :drums 0 :bass 1 :synth 2 2))

(defn- comb-lengths [^double sr]
  (mapv #(long (* sr (/ (double %) 44100.0))) [1116 1188 1277 1356]))

(defn- allpass-lengths [^double sr]
  (mapv #(long (* sr (/ (double %) 44100.0))) [556 441]))

(defn- reverb-channel [^double sr ^long stereo-offset]
  {:combs (mapv #(double-array (+ (long %) stereo-offset)) (comb-lengths sr))
   :comb-idx (long-array 4)
   :comb-lp (double-array 4)
   :allpasses (mapv #(double-array (+ (long %) stereo-offset)) (allpass-lengths sr))
   :ap-idx (long-array 2)})

(defn- reverb-sample ^double [ch ^double x]
  (let [{:keys [combs ^longs comb-idx ^doubles comb-lp allpasses ^longs ap-idx]} ch
        feedback 0.84
        damp 0.25
        input (* x 0.015)
        sum (loop [i 0 acc 0.0]
              (if (< i 4)
                (let [^doubles buf (combs i)
                      idx (aget comb-idx i)
                      y (aget buf idx)
                      lp (+ (* y (- 1.0 damp)) (* (aget comb-lp i) damp))]
                  (aset comb-lp i lp)
                  (aset buf idx (+ input (* lp feedback)))
                  (aset comb-idx i (let [n (inc idx)] (if (>= n (alength buf)) 0 n)))
                  (recur (inc i) (+ acc y)))
                acc))]
    (loop [i 0 s (double sum)]
      (if (< i 2)
        (let [^doubles buf (allpasses i)
              idx (aget ap-idx i)
              b (aget buf idx)
              out (- b s)]
          (aset buf idx (+ s (* b 0.5)))
          (aset ap-idx i (let [n (inc idx)] (if (>= n (alength buf)) 0 n)))
          (recur (inc i) out))
        s))))

(defn make-mixer [sample-rate]
  (let [sr (double sample-rate)
        inbox (ConcurrentLinkedQueue.)
        pending (PriorityQueue. 64 (comparator (fn [^Scheduled a ^Scheduled b] (< (.frame a) (.frame b)))))
        active (ArrayList.)
        buses (vec (repeatedly 6 #(double-array block)))
        out-l (double-array block)
        out-r (double-array block)
        position (long-array 1)
        duck-at (long-array [Long/MIN_VALUE])
        duck-queue (PriorityQueue.)
        delay-len (long (* 2 sr))
        delay-l (double-array delay-len)
        delay-r (double-array delay-len)
        delay-idx (long-array 1)
        delay-frames (long-array [(long (* sr 0.35))])
        rev-l (reverb-channel sr 0)
        rev-r (reverb-channel sr 23)
        duck-release (* sr 0.16)
        duck-attack (* sr 0.004)]
    {:sample-rate sr
     :inbox inbox
     :out-l out-l
     :out-r out-r
     :position (fn ^long [] (aget position 0))
     :set-tempo! (fn [bpm]
                   (aset delay-frames 0 (long (min (dec delay-len) (* sr (/ 60.0 (double bpm)) 0.75)))))
     :render!
     (fn []
       (let [start (aget position 0)
             end (+ start block)]
         (loop []
           (when-let [^Scheduled s (.poll inbox)]
             (.add pending s)
             (recur)))
         (loop []
           (let [^Scheduled s (.peek pending)]
             (when (and s (< (.frame s) end))
               (.poll pending)
               (let [from (max 0 (- (.frame s) start))]
                 (.add active (Active. (.bus s) (.voice s) (long-array [from])))
                 (when (.duck s) (.add duck-queue (max (.frame s) start))))
               (recur))))
         (doseq [^doubles b buses] (java.util.Arrays/fill b 0.0))
         (let [^Iterator it (.iterator active)]
           (loop []
             (when (.hasNext it)
               (let [^Active a (.next it)
                     bi (* 2 (.bus a))
                     alive (.render ^Voice (.voice a) (buses bi) (buses (inc bi)) (aget ^longs (.from a) 0) block)]
                 (aset ^longs (.from a) 0 0)
                 (when-not alive (.remove it)))
               (recur))))
         (let [^doubles dl (buses 0) ^doubles dr (buses 1)
               ^doubles bl (buses 2) ^doubles br (buses 3)
               ^doubles sl (buses 4) ^doubles sr* (buses 5)
               dframes (aget delay-frames 0)]
           (dotimes [i block]
             (let [frame (+ start i)]
               (loop []
                 (let [q (.peek duck-queue)]
                   (when (and q (<= (long q) frame))
                     (aset duck-at 0 (long (.poll duck-queue)))
                     (recur))))
               (let [since (double (- frame (aget duck-at 0)))
                     env (double (cond
                                   (neg? since) 0.0
                                   (< since duck-attack) (/ since duck-attack)
                                   :else (Math/exp (/ (- duck-attack since) duck-release))))
                     duck (- 1.0 (* 0.75 env))
                     widx (aget delay-idx 0)
                     ridx (let [x (- widx dframes)] (if (neg? x) (+ x delay-len) x))
                     dl* (aget delay-l ridx)
                     dr* (aget delay-r ridx)
                     synth-l (aget sl i)
                     synth-r (aget sr* i)
                     _ (aset delay-l widx (+ (* 0.5 (+ synth-l synth-r)) (* 0.38 dr*)))
                     _ (aset delay-r widx (* 0.38 dl*))
                     _ (aset delay-idx 0 (let [n (inc widx)] (if (>= n delay-len) 0 n)))
                     verb-in (+ synth-l synth-r (* 0.3 (+ (aget bl i) (aget br i))) (* 0.15 (+ (aget dl i) (aget dr i))))
                     vl (reverb-sample rev-l verb-in)
                     vr (reverb-sample rev-r verb-in)
                     ml (+ (aget dl i) (* duck (+ (aget bl i) synth-l (* 0.22 dl*))) (* 0.9 vl))
                     mr (+ (aget dr i) (* duck (+ (aget br i) synth-r (* 0.22 dr*))) (* 0.9 vr))]
                 (aset out-l i (Math/tanh (* 0.9 ml)))
                 (aset out-r i (Math/tanh (* 0.9 mr)))))))
         (aset position 0 end)))}))

(defn submit! [mixer ^long frame params seed]
  (let [voice (voices/make-voice params (:sample-rate mixer) seed)]
    (.add ^ConcurrentLinkedQueue (:inbox mixer)
          (Scheduled. frame (bus-index (:bus params)) (boolean (:duck params)) voice))))
