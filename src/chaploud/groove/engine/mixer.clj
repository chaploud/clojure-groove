(ns ^:no-doc chaploud.groove.engine.mixer
  (:require [chaploud.groove.engine.voices :as voices])
  (:import [chaploud.groove.engine.voices Voice]
           [clojure.lang IFn$DD IFn$OLDD]
           [java.util ArrayList Iterator PriorityQueue]
           [java.util.concurrent ConcurrentLinkedQueue]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:const block 256)

(deftype Scheduled [^long frame ^long bus ^boolean duck choke ^double delay ^double reverb ^Voice voice])

(deftype Active [^long bus ^Voice voice choke ^double delay ^double reverb ^longs from ^longs fade])

(def ^:private ^:const choke-frames 240)

(defn- bus-index ^long [k]
  (if (= k :drums) 0 1))

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
        dry (vec (repeatedly 4 #(double-array block)))
        ^doubles scratch-l (double-array block)
        ^doubles scratch-r (double-array block)
        ^doubles delay-in (double-array block)
        ^doubles verb-in (double-array block)
        out-l (double-array block)
        out-r (double-array block)
        position (long-array 1)
        duck-at (long-array [Long/MIN_VALUE])
        duck-queue (PriorityQueue.)
        delay-len (long (* 2 sr))
        delay-l (double-array delay-len)
        delay-r (double-array delay-len)
        delay-idx (long-array 1)
        delay-frames (long-array 1)
        feedback (double-array 1)
        duck-depth (double-array 1)
        [^IFn$DD rev-l reset-l] (make-reverb sr 0)
        [^IFn$DD rev-r reset-r] (make-reverb sr 23)
        resets (long-array 1)
        poisoned (boolean-array 1)
        duck-release (* sr 0.16)
        duck-attack (* sr 0.004)]
    {:sample-rate sr
     :inbox inbox
     :out-l out-l
     :out-r out-r
     :position (fn ^long [] (aget position 0))
     :take-non-finite-resets! (fn ^long [] (let [n (aget resets 0)] (aset resets 0 0) n))
     :set-globals! (fn [{:keys [tempo delay-feedback] :as settings}]
                     (aset delay-frames 0 (long (min (dec delay-len) (* sr (/ 60.0 (double tempo)) 0.75))))
                     (aset feedback 0 (double delay-feedback))
                     (aset duck-depth 0 (double (:duck-depth settings))))
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
                 (when-let [group (.choke s)]
                   (doseq [^Active other active
                           :when (and (= group (.choke other)) (neg? (aget ^longs (.fade other) 0)))]
                     (aset ^longs (.fade other) 0 (+ choke-frames (max 0 (- (- (.frame s) start) (aget ^longs (.from other) 0)))))))
                 (.add active (Active. (.bus s) (.voice s) (.choke s) (.delay s) (.reverb s)
                                       (long-array [from]) (long-array [-1])))
                 (when (.duck s) (.add duck-queue (max (.frame s) start))))
               (recur))))
         (dotimes [b 4] (java.util.Arrays/fill ^doubles (dry b) 0.0))
         (java.util.Arrays/fill delay-in 0.0)
         (java.util.Arrays/fill verb-in 0.0)
         (let [^Iterator it (.iterator active)]
           (loop []
             (when (.hasNext it)
               (let [^Active a (.next it)
                     from (aget ^longs (.from a) 0)
                     ^longs fade (.fade a)
                     _ (java.util.Arrays/fill scratch-l 0.0)
                     _ (java.util.Arrays/fill scratch-r 0.0)
                     alive (.render ^Voice (.voice a) scratch-l scratch-r from block)
                     bi (* 2 (.bus a))
                     ^doubles out-l* (dry bi)
                     ^doubles out-r* (dry (inc bi))
                     send-d (* 0.5 (.delay a))
                     send-r (.reverb a)
                     faded (loop [i from]
                             (if (< i block)
                               (let [f (aget fade 0)
                                     g (double (cond
                                                 (neg? f) 1.0
                                                 (> f choke-frames) 1.0
                                                 :else (/ (double f) choke-frames)))
                                     l (* g (aget scratch-l i))
                                     r (* g (aget scratch-r i))]
                                 (when (pos? f) (aset fade 0 (dec f)))
                                 (aset out-l* i (+ (aget out-l* i) l))
                                 (aset out-r* i (+ (aget out-r* i) r))
                                 (aset delay-in i (+ (aget delay-in i) (* send-d (+ l r))))
                                 (aset verb-in i (+ (aget verb-in i) (* send-r (+ l r))))
                                 (recur (inc i)))
                               (zero? (aget fade 0))))]
                 (aset ^longs (.from a) 0 0)
                 (when (or (not alive) faded) (.remove it)))
               (recur))))
         (let [^doubles dl (dry 0) ^doubles dr (dry 1)
               ^doubles bl (dry 2) ^doubles br (dry 3)
               dframes (aget delay-frames 0)
               fb (aget feedback 0)]
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
                     duck (- 1.0 (* (aget duck-depth 0) env))
                     widx (aget delay-idx 0)
                     ridx (let [x (- widx dframes)] (if (neg? x) (+ x delay-len) x))
                     dl* (aget delay-l ridx)
                     dr* (aget delay-r ridx)
                     _ (aset delay-l widx (+ (aget delay-in i) (* fb dr*)))
                     _ (aset delay-r widx (* fb dl*))
                     _ (aset delay-idx 0 (let [n (inc widx)] (if (>= n delay-len) 0 n)))
                     vin (aget verb-in i)
                     vl (.invokePrim rev-l vin)
                     vr (.invokePrim rev-r vin)
                     ml (+ (aget dl i) (* duck (+ (aget bl i) (* 0.22 dl*))) (* 0.9 vl))
                     mr (+ (aget dr i) (* duck (+ (aget br i) (* 0.22 dr*))) (* 0.9 vr))]
                 (if (and (Double/isFinite ml) (Double/isFinite mr))
                   (do (aset out-l i (Math/tanh (* 0.9 ml)))
                       (aset out-r i (Math/tanh (* 0.9 mr))))
                   (do (aset poisoned 0 true)
                       (aset out-l i 0.0)
                       (aset out-r i 0.0))))))
           (when (aget poisoned 0)
             (java.util.Arrays/fill delay-l 0.0)
             (java.util.Arrays/fill delay-r 0.0)
             (reset-l)
             (reset-r)
             (aset poisoned 0 false)
             (aset resets 0 (inc (aget resets 0)))))
         (aset position 0 end)))}))

(defn submit! [mixer ^long frame params seed]
  (let [voice (voices/make-voice params (:sample-rate mixer) seed)]
    (.add ^ConcurrentLinkedQueue (:inbox mixer)
          (Scheduled. frame (bus-index (:bus params)) (boolean (:duck params)) (:choke params)
                      (double (:delay params 0.0)) (double (:reverb params 0.0)) voice))))
