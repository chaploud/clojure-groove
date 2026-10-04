(ns jam
  (:require [chaploud.groove :as g]))

;; A scratchpad for live coding. Evaluate one form at a time; changes land at the next bar.
;; docs/guide.md walks through the same ideas step by step.

(comment
  (g/start!)
  (g/tempo 126)
  (g/globals! {:root :a :scale :minor})

  ;; drums: write them, or start from a part
  (g/drum :kick "x... x... x... x...")
  (g/drum :hat "..x. ..x. ..x. ..xX" :vel 0.6)
  (g/play :drums [:beat/techno {:kit :kit/hard}])
  (g/clear :kick :hat)

  ;; a line that slides, with a filter that moves
  (g/synth :acid [:s 0 0 4 0 2 {:degree 0 :glide true} 6 0 0 3 0 {:degree 2 :glide true} 7 0 4 2]
           :cutoff [:lfo :tri 8 180.0 900.0])

  ;; one progression for pad, arpeggio and bass
  (g/play :pad [:prog/epic {:octave 3}])
  (g/play :arp [:fx [:arp :up-down 1/16] [:prog/epic {:inst :synth/pluck :vel 0.6 :delay 0.7}]])
  (g/clear :acid)
  (g/play :bass [:prog/epic {:inst :synth/bass :voicing :root}])

  ;; mini-notation for dense rhythms
  (g/mini :perc "rim(5,16,3), cb(3,8)" :vel 0.5)

  ;; find more
  (g/browse)
  (g/browse "garage")
  (g/describe :beat/two-step)
  (g/audition :beat/two-step)
  (g/audition nil)

  ;; perform
  (g/drum :snare "..x. ..x. ..xx xxxx" :if :fill)
  (g/fill 1)
  (g/mute :pad)
  (g/unmute)
  (g/snap! :drop)
  (g/scene! :break {:drums nil :bass nil})
  (g/arrange! [[:break 4] [:drop 8]])

  ;; files
  (g/save! "out/jam.edn")
  (g/render! "out/jam.wav" 8)
  (g/load! "synthwave")

  (g/hush)
  (g/stop!))
