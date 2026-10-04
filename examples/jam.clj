(ns jam
  (:require [chaploud.groove :as g]))

;; Evaluate one form at a time from your editor. Changes are picked up at the next bar.

(comment
  (g/start!)
  (g/tempo 124)
  (g/globals! {:root :a :scale :minor})

  ;; 1. Drum pad: one character per 16th step. x hit, X accent, . rest; spaces and | are ignored.
  (g/drum :kick "x... x... x... x...")
  (g/drum :clap ".... x... .... x...")
  (g/drum :hat "..x. ..x. ..x. ..xX" :vel 0.6)
  (g/show :hat)

  ;; 2. Melody as scale degrees. :s :e :q :h :w set the length for what follows.
  (g/synth :acid [:s 0 0 7 0 3 0 10 0 0 5 0 3 12 0 7 3])
  (g/synth :pad [:w #{0 2 4} #{-2 0 2}] :octave 3)

  ;; 3. Tweak sounds by overriding parameters, the same way as any other attribute.
  (g/synth :acid [:s 0 0 7 0 3 0 10 0] :cutoff 600.0 :res 0.9)

  ;; 4. Mini-notation for denser rhythms.
  (g/mini :perc "rim(5,16,3), cb(3,8)" :vel 0.5)

  ;; 5. Name parts and reuse them. Attributes at the reference site win.
  (g/put! :clip/motif [:notes {:inst :synth/pluck} [:s 0 2 4 7 4 2 0 :_]])
  (g/play! :lead [:seq :clip/motif [:fx [:transpose 5] :clip/motif]])
  (g/play! :echo [:clip/motif {:octave 6 :vel 0.3}])

  ;; 6. Performance controls
  (g/fill! 1)
  (g/drum :snare "..x. ..x. ..xx xxxx" :if :fill)
  (g/mute :kick)
  (g/unmute)
  (g/solo :acid :kick)
  (g/unsolo)

  ;; 7. Scenes and arrangement
  (g/snap! :drop)
  (g/scene! :break {:kick nil :clap nil :acid nil})
  (g/launch! :break)
  (g/arrange! [[:break 4] [:drop 8]])

  ;; 8. Files
  (g/save! "out/jam.edn")
  (g/load! "examples/trance.edn")
  (g/render! "out/jam.wav" 8)

  (g/hush)
  (g/stop!))
