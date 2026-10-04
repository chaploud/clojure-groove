(ns chaploud.groove.engine.mixer
  (:require [chaploud.groove.engine.voices :as voices])
  (:import [chaploud.groove.engine.voices Voice]
           [clojure.lang IFn$DD IFn$OLDD]
           [java.util ArrayList Iterator PriorityQueue]
           [java.util.concurrent ConcurrentLinkedQueue]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:const block 256)

(deftype Scheduled [^long frame ^long bus ^boolean duck ^Voice voice])

(deftype Active [^long bus ^Voice voice ^longs from])

(defn- bus-index ^long [k]
  (case k :drums 0 :bass 1 :synth 2 2))

(defn- make-reverb [^double sr ^long stereo-offset]
  (let [scale (fn [n] (+ (long (* sr (/ (double n) 44100.0))) stereo-offset))
        c0 (double-array (scale 1116)) c1 (double-array (scale 1188))
        c2 (double-array (scale 1277)) c3 (double-array (scale 1356))
        a0 (double-array (scale 556)) a1 (double-array (scale 441))
        idx (long-array 6)
        lp (double-array 4)
        comb (fn ^double [^doubles buf ^long i ^double input]
               (let [k (aget idx i)
                     y (aget buf k)
                     f (+ (* y 0.75) (* (aget lp i) 0.25))]
                 (aset lp i f)
                 (aset buf k (+ input (* f 0.84)))
                 (aset idx i (let [n (inc k)] (if (>= n (alength buf)) 0 n)))
                 y))
        allpass (fn ^double [^doubles buf ^long i ^double x]
                  (let [k (aget idx i)
                        b (aget buf k)]
                    (aset buf k (+ x (* b 0.5)))
                    (aset idx i (let [n (inc k)] (if (>= n (alength buf)) 0 n)))
                    (- b x)))]
    [(fn ^double [^double x]
       (let [in (* x 0.015)
             sum (+ (.invokePrim ^IFn$OLDD comb c0 0 in) (.invokePrim ^IFn$OLDD comb c1 1 in)
                    (.invokePrim ^IFn$OLDD comb c2 2 in) (.invokePrim ^IFn$OLDD comb c3 3 in))]
         (.invokePrim ^IFn$OLDD allpass a1 5 (.invokePrim ^IFn$OLDD allpass a0 4 sum))))
     (fn []
       (doseq [^doubles a [c0 c1 c2 c3 a0 a1 lp]] (java.util.Arrays/fill a 0.0)))]))

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
        [^IFn$DD rev-l reset-l] (make-reverb sr 0)
        [^IFn$DD rev-r reset-r] (make-reverb sr 23)
        resets (long-array 1)
        duck-release (* sr 0.16)
        duck-attack (* sr 0.004)]
    {:sample-rate sr
     :inbox inbox
     :out-l out-l
     :out-r out-r
     :position (fn ^long [] (aget position 0))
     :non-finite-resets (fn ^long [] (aget resets 0))
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
         (dotimes [b 6] (java.util.Arrays/fill ^doubles (buses b) 0.0))
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
                     vl (.invokePrim rev-l verb-in)
                     vr (.invokePrim rev-r verb-in)
                     ml (+ (aget dl i) (* duck (+ (aget bl i) synth-l (* 0.22 dl*))) (* 0.9 vl))
                     mr (+ (aget dr i) (* duck (+ (aget br i) synth-r (* 0.22 dr*))) (* 0.9 vr))]
                 (if (and (Double/isFinite ml) (Double/isFinite mr))
                   (do (aset out-l i (Math/tanh (* 0.9 ml)))
                       (aset out-r i (Math/tanh (* 0.9 mr))))
                   (do (java.util.Arrays/fill delay-l 0.0)
                       (java.util.Arrays/fill delay-r 0.0)
                       (reset-l)
                       (reset-r)
                       (aset resets 0 (inc (aget resets 0)))
                       (aset out-l i 0.0)
                       (aset out-r i 0.0)))))))
         (aset position 0 end)))}))

(defn submit! [mixer ^long frame params seed]
  (let [voice (voices/make-voice params (:sample-rate mixer) seed)]
    (.add ^ConcurrentLinkedQueue (:inbox mixer)
          (Scheduled. frame (bus-index (:bus params)) (boolean (:duck params)) voice))))
