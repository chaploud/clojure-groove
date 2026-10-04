(ns chaploud.groove.instruments)

(def builtin
  {:drum/kick {:voice :kick :bus :drums :duck true :gain 1.0 :length 0.9
               :pitch 46.0 :punch 170.0 :pitch-decay 30.0 :decay 0.28 :click 0.35 :drive 2.4}
   :drum/snare {:voice :snare :bus :drums :gain 0.55 :length 0.6 :tone 190.0 :snappy 0.9 :decay 0.16}
   :drum/clap {:voice :clap :bus :drums :gain 0.6 :length 0.7 :decay 0.16 :pan 0.05}
   :drum/hat {:voice :metal :bus :drums :choke :hats :gain 0.8 :length 0.2 :decay 0.035 :tune 1.0 :cutoff 7500.0 :pan 0.2}
   :drum/open-hat {:voice :metal :bus :drums :choke :hats :gain 0.6 :length 1.0 :decay 0.28 :tune 1.0 :cutoff 7000.0 :pan 0.2}
   :drum/ride {:voice :metal :bus :drums :gain 0.4 :length 2.0 :decay 0.7 :tune 1.6 :cutoff 5000.0 :pan -0.25}
   :drum/crash {:voice :metal :bus :drums :gain 0.5 :length 3.0 :decay 1.2 :tune 1.2 :cutoff 4000.0 :pan -0.1}
   :drum/rim {:voice :rim :bus :drums :gain 0.4 :length 0.1 :pan -0.15}
   :drum/cowbell {:voice :cowbell :bus :drums :gain 0.4 :length 0.6 :decay 0.18 :pan 0.25}
   :drum/tom-low {:voice :tom :bus :drums :gain 0.7 :length 0.8 :pitch 82.0 :decay 0.25 :pan -0.3}
   :drum/tom-mid {:voice :tom :bus :drums :gain 0.7 :length 0.8 :pitch 120.0 :decay 0.22}
   :drum/tom-high {:voice :tom :bus :drums :gain 0.7 :length 0.7 :pitch 170.0 :decay 0.2 :pan 0.3}

   :synth/acid {:voice :synth :bus :bass :osc :saw :gain 0.45 :octave 2
                :cutoff 260.0 :env 2400.0 :fdecay 0.17 :res 0.85
                :attack 0.002 :decay 0.25 :sustain 0.7 :release 0.03 :drive 2.0 :gate 0.7}
   :synth/bass {:voice :synth :bus :bass :osc :square :sub 0.6 :gain 0.4 :octave 2
                :cutoff 520.0 :env 900.0 :fdecay 0.08 :res 0.25
                :attack 0.003 :decay 0.2 :sustain 0.8 :release 0.05 :drive 1.6 :gate 0.8}
   :synth/sub {:voice :synth :bus :bass :osc :sine :gain 0.6 :octave 1
               :cutoff 400.0 :res 0.0 :attack 0.005 :decay 0.3 :sustain 0.9 :release 0.08 :gate 0.9}
   :synth/reese {:voice :synth :bus :bass :osc :supersaw :unison 3 :detune 22.0 :spread 0.3 :sub 0.4
                 :gain 0.4 :octave 1 :cutoff 700.0 :env 300.0 :fdecay 0.5 :res 0.3
                 :attack 0.01 :decay 0.4 :sustain 0.9 :release 0.1 :drive 1.8 :gate 1.0}
   :synth/supersaw {:voice :synth :bus :synth :osc :supersaw :unison 7 :detune 28.0 :spread 0.9
                    :gain 0.32 :octave 4 :cutoff 3800.0 :env 1800.0 :fdecay 0.25 :res 0.15
                    :attack 0.004 :decay 0.3 :sustain 0.75 :release 0.18 :drive 1.2 :gate 0.85}
   :synth/pad {:voice :synth :bus :synth :osc :supersaw :unison 5 :detune 18.0 :spread 1.0
               :gain 0.22 :octave 4 :cutoff 1600.0 :env 400.0 :fdecay 1.5 :res 0.1
               :attack 0.35 :decay 1.0 :sustain 0.8 :release 0.9 :gate 1.0}
   :synth/pluck {:voice :synth :bus :synth :osc :saw :gain 0.35 :octave 4
                 :cutoff 500.0 :env 4500.0 :fdecay 0.09 :res 0.3
                 :attack 0.001 :decay 0.18 :sustain 0.0 :release 0.12 :gate 0.5}
   :synth/lead {:voice :synth :bus :synth :osc :square :gain 0.25 :octave 5
                :cutoff 2400.0 :env 1200.0 :fdecay 0.2 :res 0.35
                :attack 0.005 :decay 0.2 :sustain 0.7 :release 0.12 :drive 1.4 :gate 0.8}
   :synth/keys {:voice :synth :bus :synth :osc :tri :gain 0.4 :octave 4
                :cutoff 2200.0 :env 800.0 :fdecay 0.3 :res 0.05
                :attack 0.003 :decay 0.6 :sustain 0.3 :release 0.3 :gate 0.9}})

(def default-kit
  {:bd :drum/kick :sd :drum/snare :cp :drum/clap :hh :drum/hat :oh :drum/open-hat :rim :drum/rim
   :lt :drum/tom-low :mt :drum/tom-mid :ht :drum/tom-high :cb :drum/cowbell :cr :drum/crash :rd :drum/ride})

(defn- resolve-with-base [defs builtins k what]
  (loop [k k, seen #{}, acc {}]
    (cond
      (nil? k) acc
      (seen k) (throw (ex-info (str "Circular " what " :base through " k) {what k}))
      :else (let [m (or (get defs k) (get builtins k)
                        (throw (ex-info (str "Unknown " what " " k) {what k})))]
              (recur (:base m) (conj seen k) (merge (dissoc m :base) acc))))))

(defn resolve-instrument [instruments k]
  (resolve-with-base instruments builtin k "instrument"))

(defn resolve-kit [kits k]
  (resolve-with-base kits {:kit/default default-kit} k "kit"))

(defn instrument-key [kits {:keys [inst kit]}]
  (if (simple-keyword? inst)
    (let [k (or kit :kit/default)]
      (or (get (resolve-kit kits k) inst)
          (throw (ex-info (str "Kit " k " has no " inst) {:kit k :role inst}))))
    inst))

(def ^:private bus-sends
  {:drums {:delay 0.0 :reverb 0.15}
   :bass {:delay 0.0 :reverb 0.3}
   :synth {:delay 1.0 :reverb 1.0}})

(defn resolve-event [{:keys [instruments kits]} event]
  (let [inst (or (:inst event) (throw (ex-info "Event has no :inst" {:event event})))
        m (merge (resolve-instrument instruments (instrument-key kits (assoc event :inst inst))) event)]
    (merge (bus-sends (:bus m) (:synth bus-sends)) m)))

(def param-docs
  {:voice "which synthesis voice plays it (:synth or a drum voice)"
   :bus ":drums are not ducked; :bass and :synth are ducked by the kick"
   :duck "true on an instrument that ducks the other buses (the kick)"
   :choke "voices in the same group cut each other off"
   :gain "output level"
   :pan "-1 left .. 1 right"
   :delay "send to the tempo-synced delay, 0-1"
   :reverb "send to the reverb, 0-1"
   :length "longest a drum hit can ring, seconds"
   :decay "decay time, seconds"
   :pitch "base frequency in Hz (kick, toms)"
   :punch "how far the kick's pitch sweeps above :pitch, Hz"
   :pitch-decay "how fast the kick's sweep falls, per second"
   :click "level of the kick's transient"
   :drive "saturation; 1 is clean"
   :tone "body frequency of the snare, Hz"
   :snappy "level of the snare's noise"
   :tune "pitch multiplier of metallic cymbals"
   :cutoff "filter frequency, Hz (lowpass on synths, highpass on cymbals)"
   :osc ":saw :square :sine :tri or :supersaw"
   :unison "number of detuned oscillators for :supersaw"
   :detune "spread of the unison oscillators, cents"
   :spread "stereo width of the unison oscillators, 0-1"
   :sub "level of a sine one octave below"
   :env "how far the filter envelope opens above :cutoff, Hz"
   :fdecay "filter envelope decay, seconds"
   :res "filter resonance, 0-1"
   :attack "amplitude attack, seconds"
   :sustain "amplitude sustain level, 0-1"
   :release "amplitude release, seconds"
   :octave "default octave for scale degrees"
   :gate "fraction of the note length the note is held"
   :glide-time "how long a {:glide true} slide takes, seconds"})
