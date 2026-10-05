(ns ^:no-doc chaploud.groove.engine.voices
  (:require [chaploud.groove.pitch :as pitch])
  (:import [clojure.lang IFn$DDD IFn$LD]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(definterface Voice
  (^boolean render [^doubles l ^doubles r ^long from ^long to]))

(def ^:const TAU (* 2.0 Math/PI))

(defn- noise-source ^IFn$LD [^long seed]
  (let [s (long-array [(bit-or seed 1)])]
    (fn ^double [^long _]
      (let [x (aget s 0)
            x (bit-xor x (bit-shift-left x 13))
            x (bit-xor x (unsigned-bit-shift-right x 7))
            x (bit-xor x (bit-shift-left x 17))]
        (aset s 0 x)
        (/ (double (bit-and x 0xffffff)) 8388608.0 -1.0)))))

(defn- blep ^double [^double t ^double dt]
  (cond
    (< t dt) (let [x (/ t dt)] (- (+ x x) (* x x) 1.0))
    (> t (- 1.0 dt)) (let [x (/ (- t 1.0) dt)] (+ (* x x) x x 1.0))
    :else 0.0))

(defn- oscillator ^double [^long kind ^double phase ^double dt]
  (case kind
    0 (- (* 2.0 phase) 1.0 (blep phase dt))
    1 (let [p2 (let [p (+ phase 0.5)] (if (>= p 1.0) (- p 1.0) p))]
        (+ (if (< phase 0.5) 1.0 -1.0) (blep phase dt) (- (blep p2 dt))))
    2 (Math/sin (* TAU phase))
    3 (- 1.0 (* 4.0 (Math/abs (- phase 0.5))))))

(defn- osc-kind ^long [k]
  (case k :saw 0 :square 1 :sine 2 :tri 3 :supersaw 0 0))

(defmacro ^:private svf-step [st x g k out]
  (let [[s xx gg kk ic1 ic2 a1 a2 a3 v1 v2 v3] (repeatedly 12 gensym)]
    `(let [~s ~st
           ~xx ~x
           ~gg ~g
           ~kk ~k
           ~ic1 (aget ~s 0)
           ~ic2 (aget ~s 1)
           ~a1 (/ 1.0 (+ 1.0 (* ~gg (+ ~gg ~kk))))
           ~a2 (* ~gg ~a1)
           ~a3 (* ~gg ~a2)
           ~v3 (- ~xx ~ic2)
           ~v1 (+ (* ~a1 ~ic1) (* ~a2 ~v3))
           ~v2 (+ ~ic2 (* ~a2 ~ic1) (* ~a3 ~v3))]
       (aset ~s 0 (- (* 2.0 ~v1) ~ic1))
       (aset ~s 1 (- (* 2.0 ~v2) ~ic2))
       ~(case out
          :low v2
          :band v1
          :high `(- ~xx (* ~kk ~v1) ~v2)))))

(defn lowpass ^double [^doubles st ^double x ^double g ^double k] (svf-step st x g k :low))
(defn bandpass ^double [^doubles st ^double x ^double g ^double k] (svf-step st x g k :band))
(defn highpass ^double [^doubles st ^double x ^double g ^double k] (svf-step st x g k :high))

(defn- g-of ^double [^double hz ^double sr]
  (Math/tan (* Math/PI (/ (Math/min hz (* 0.45 sr)) sr))))

(defn- k-of ^double [^double res]
  (- 2.0 (* 1.96 (Math/min 1.0 (Math/max 0.0 res)))))

(defn- decay-env ^double [^double t ^double a ^double d ^double s]
  (if (< t a)
    (/ t a)
    (+ s (* (- 1.0 s) (Math/exp (/ (- a t) (Math/max d 1.0E-4)))))))

(defn- pan-gains [p]
  (let [a (* (/ Math/PI 4.0) (+ 1.0 (double (Math/max -1.0 (Math/min 1.0 (double (or p 0.0)))))))]
    [(Math/cos a) (Math/sin a)]))

;; ---------------------------------------------------------------- drums

(defn- percussion [^IFn$DDD sample-fn length gain p sr seed]
  (let [length (double length)
        gain (double gain)
        sr (double sr)
        noise ^IFn$LD (noise-source seed)
        [gl gr] (pan-gains p)
        gl (* gain (double gl))
        gr (* gain (double gr))
        n (long-array 1)
        end (long (* length sr))
        fade (* 0.005 sr)]
    (reify Voice
      (render [_ l r from to]
        (loop [i from, j (aget n 0)]
          (if (and (< i to) (< j end))
            (let [s (* (.invokePrim sample-fn (/ (double j) sr) (.invokePrim noise j))
                       (Math/min 1.0 (/ (double (- end j)) fade)))]
              (aset l i (+ (aget l i) (* gl s)))
              (aset r i (+ (aget r i) (* gr s)))
              (recur (inc i) (inc j)))
            (do (aset n 0 j) (< j end))))))))

(defn- kick-fn [{:keys [pitch punch pitch-decay decay click drive]} sr]
  (let [pitch (double pitch) punch (double punch) pitch-decay (double pitch-decay)
        decay (double decay) click (double click) drive (double drive)
        norm (Math/tanh drive)
        phase (double-array 1)
        sr (double sr)]
    (fn ^double [^double t ^double nz]
      (let [f (+ pitch (* punch (Math/exp (- (* t pitch-decay)))))
            ph (+ (aget phase 0) (/ f sr))
            _ (aset phase 0 (- ph (Math/floor ph)))
            body (* (Math/sin (* TAU ph)) (Math/exp (/ (- t) decay)))
            c (* click nz (Math/exp (* t -400.0)))]
        (/ (Math/tanh (* drive (+ body c))) norm)))))

(defn- snare-fn [{:keys [tone snappy decay]} sr]
  (let [tone (double tone) snappy (double snappy) decay (double decay) sr (double sr)
        st (double-array 2)
        g (g-of 1800.0 sr)
        k (k-of 0.2)]
    (fn ^double [^double t ^double nz]
      (let [body (* (+ (Math/sin (* TAU tone t)) (* 0.5 (Math/sin (* TAU 1.6 tone t))))
                    (Math/exp (/ (- t) 0.07)))
            hiss (* snappy (highpass st nz g k) (Math/exp (/ (- t) decay)))]
        (* 0.7 (+ (* 0.6 body) hiss))))))

(defn- clap-fn [{:keys [decay]} sr]
  (let [decay (double decay) sr (double sr)
        st (double-array 2)
        g (g-of 1300.0 sr)
        k (k-of 0.55)]
    (fn ^double [^double t ^double nz]
      (let [bp (bandpass st nz g k)
            burst (if (< t 0.03)
                    (Math/exp (/ (- (rem t 0.0105)) 0.004))
                    (Math/exp (/ (- (- t 0.03)) decay)))]
        (* 2.2 bp burst)))))

(def ^:private metal-ratios [1.0 1.4826 1.8007 2.5459 2.6302 3.8975])

(defn- metal-fn [{:keys [decay tune cutoff]} sr]
  (let [decay (double decay) sr (double sr)
        base (* 205.3 (double tune))
        incs (double-array (map #(/ (* base (double %)) sr) metal-ratios))
        phases (double-array 6)
        sum (double-array 1)
        st (double-array 2) st2 (double-array 2)
        g (g-of (double cutoff) sr)
        k (k-of 0.3)]
    (fn ^double [^double t ^double nz]
      (aset sum 0 0.0)
      (dotimes [i 6]
        (let [p (+ (aget phases i) (aget incs i))
              p (if (>= p 1.0) (- p 1.0) p)]
          (aset phases i p)
          (aset sum 0 (+ (aget sum 0) (if (< p 0.5) 1.0 -1.0)))))
      (let [sq (aget sum 0)
            x (+ (* 0.12 sq) (* 0.5 nz))
            hp (highpass st2 (highpass st x g k) g k)]
        (* 0.9 hp (Math/exp (/ (- t) decay)))))))

(defn- tom-fn [{:keys [pitch decay]} sr]
  (let [pitch (double pitch) decay (double decay) sr (double sr)
        phase (double-array 1)]
    (fn ^double [^double t ^double nz]
      (let [f (* pitch (+ 1.0 (* 0.6 (Math/exp (* t -18.0)))))
            ph (+ (aget phase 0) (/ f sr))]
        (aset phase 0 (- ph (Math/floor ph)))
        (* (+ (Math/sin (* TAU ph)) (* 0.08 nz (Math/exp (* t -60.0))))
           (Math/exp (/ (- t) decay)))))))

(defn- rim-fn [_ _]
  (fn ^double [^double t ^double nz]
    (* (+ (Math/sin (* TAU 820.0 t)) (* 0.6 (Math/sin (* TAU 1690.0 t))) (* 0.4 nz))
       (Math/exp (/ (- t) 0.012)))))

(defn- cowbell-fn [{:keys [decay]} sr]
  (let [decay (double decay) sr (double sr)
        st (double-array 2)
        g (g-of 900.0 sr) k (k-of 0.6)]
    (fn ^double [^double t ^double _]
      (let [sq (+ (if (< (rem (* 540.0 t) 1.0) 0.5) 1.0 -1.0)
                  (if (< (rem (* 800.0 t) 1.0) 0.5) 1.0 -1.0))]
        (* 0.5 (bandpass st sq g k) (Math/exp (/ (- t) decay)))))))

(def ^:private drum-fns
  {:kick kick-fn :snare snare-fn :clap clap-fn :metal metal-fn :tom tom-fn :rim rim-fn :cowbell cowbell-fn})

;; ---------------------------------------------------------------- synth

(defn- synth-voice
  [{:keys [osc unison detune sub cutoff env fdecay res attack decay sustain release drive gain vel pan
           dur-s midi spread glide-from-midi glide-time hp]
    :or {unison 1 detune 0.0 sub 0.0 env 0.0 fdecay 0.2 res 0.2 drive 1.0 spread 0.0 hp 0.0}}
   sr seed]
  (let [sr (double sr)
        kind (osc-kind osc)
        n-osc (long (if (= osc :supersaw) (max 1 (long unison)) 1))
        hz (pitch/midi->hz midi)
        detune (double detune)
        incs (double-array (for [i (range n-osc)
                                 :let [x (if (= 1 n-osc) 0.0 (- (/ (* 2.0 (double i)) (double (dec n-osc))) 1.0))]]
                             (/ (* hz (Math/pow 2.0 (/ (* (double x) detune) 1200.0))) sr)))
        rnd (java.util.Random. (long seed))
        phases (double-array (repeatedly n-osc #(if (= 1 n-osc) 0.0 (.nextDouble rnd))))
        spread (double spread)
        [pl pr] (pan-gains pan)
        pans (double-array (for [i (range n-osc)]
                             (if (= 1 n-osc) 0.5 (+ 0.5 (* spread (- (/ (double i) (dec n-osc)) 0.5))))))
        sub-phase (double-array 1)
        mix (double-array 2)
        sub (double sub)
        sub-inc (/ (* 0.5 hz) sr)
        st-l (double-array 2) st-r (double-array 2)
        hp? (pos? (double hp))
        hp-g (g-of (double hp) sr)
        hp-l (double-array 2) hp-r (double-array 2)
        cutoff (double cutoff) env (* (double env) (if (> (double vel) 0.95) 1.5 1.0))
        fdecay (double fdecay) k (k-of res)
        attack (double attack) decay (double decay) sustain (double sustain) release (double release)
        gate-off (double dur-s)
        end (long (* sr (+ gate-off (* 6.0 (Math/max release 0.005)))))
        drive (double drive) norm (Math/tanh drive)
        amp (* (double gain) (double vel) (/ 1.0 (Math/sqrt (double n-osc))))
        pl (* amp (double pl)) pr (* amp (double pr))
        n (long-array 1)
        glide-semis (if glide-from-midi (- (double glide-from-midi) (double midi)) 0.0)
        glide-tau (/ (double (or glide-time 0.06)) 3.0)]
    (reify Voice
      (render [_ l r from to]
        (loop [i from, j (aget n 0)]
          (if (and (< i to) (< j end))
            (let [t (/ (double j) sr)
                  bend (if (zero? glide-semis)
                         1.0
                         (Math/pow 2.0 (/ (* glide-semis (Math/exp (/ (- t) glide-tau))) 12.0)))
                  _ (loop [o 0 al 0.0 ar 0.0]
                      (if (< o n-osc)
                        (let [p (aget phases o)
                              dt (* bend (aget incs o))
                              s (oscillator kind p dt)
                              p2 (+ p dt)
                              w (aget pans o)]
                          (aset phases o (if (>= p2 1.0) (- p2 1.0) p2))
                          (recur (inc o) (+ al (* s (- 1.0 w) 2.0)) (+ ar (* s w 2.0))))
                        (do (aset mix 0 al) (aset mix 1 ar))))
                  sp (aget sub-phase 0)
                  sub-s (* sub (Math/sin (* TAU sp)))
                  _ (aset sub-phase 0 (let [x (+ sp (* bend sub-inc))] (if (>= x 1.0) (- x 1.0) x)))
                  fc (+ cutoff (* env (Math/exp (/ (- t) fdecay))))
                  g (g-of fc sr)
                  yl (lowpass st-l (+ (aget mix 0) sub-s) g k)
                  yr (lowpass st-r (+ (aget mix 1) sub-s) g k)
                  yl (if hp? (highpass hp-l yl hp-g 1.414) yl)
                  yr (if hp? (highpass hp-r yr hp-g 1.414) yr)
                  a (if (< t gate-off)
                      (decay-env t attack decay sustain)
                      (* (decay-env gate-off attack decay sustain)
                         (Math/exp (/ (- gate-off t) (Math/max release 1.0E-4)))))
                  ol (/ (Math/tanh (* drive yl)) norm)
                  or (/ (Math/tanh (* drive yr)) norm)]
              (aset l i (+ (aget l i) (* a pl ol)))
              (aset r i (+ (aget r i) (* a pr or)))
              (recur (inc i) (inc j)))
            (do (aset n 0 j) (< j end))))))))

(defn make-voice ^Voice [{:keys [voice gain vel pan length] :as params} sr seed]
  (if (= voice :synth)
    (synth-voice params sr seed)
    (let [f (or (drum-fns voice) (throw (ex-info (str "Unknown voice type " voice) {:voice voice})))]
      (percussion (f params sr) (double length) (* (double gain) (double vel)) pan sr seed))))
