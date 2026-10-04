# From an empty bar to a track

A walk through one session, about fifteen minutes with a REPL connected (`bb nrepl`). Evaluate each form and listen; every change lands at the next bar.

## 1. A beat

```clojure
(require '[chaploud.groove :as g])
(g/start!)
(g/tempo 124)

(g/drum :kick "x... x... x... x...")
(g/drum :clap ".... x... .... x...")
(g/drum :hat  "..x. ..x. ..x. ..xX")
```

Each character is a 16th note. Change a string and re-evaluate: the edit is checked first, so a typo comes back as an error while the beat keeps playing. Try `(g/drum :kick "x..q")`.

Rather than writing drums from scratch, start from the library:

```clojure
(g/clear :kick :clap :hat)
(g/browse "house")
(g/play :drums :beat/house)
(g/play :drums [:beat/house {:kit :kit/tr909 :swing 0.1}])
```

## 2. A key and a bassline

Melodies are scale degrees, so set a key once:

```clojure
(g/globals! {:root :f :scale :minor})
(g/play :bass :bass/offbeat)
(g/synth :acid [:s 0 0 4 0 2 0 6 0 0 3 0 2 7 0 4 2])
(g/show :acid)
```

Degree 0 is the root, 7 the octave above. `:s` makes the following notes 16ths; `:e`, `:q`, `:h`, `:w` work the same way.

## 3. Chords

```clojure
(g/play :pad [:prog/epic {:octave 3}])
(g/play :arp [:fx [:arp :up-down 1/16] [:prog/epic {:inst :synth/pluck :vel 0.6}]])
(g/clear :acid)
(g/play :bass [:prog/epic {:inst :synth/bass :voicing :root}])
```

One progression now drives the pad, the arpeggio and the bass, so they always agree. Swap `:prog/epic` for `:prog/sensitive` in all three and the whole harmony changes.

## 4. Movement

```clojure
(g/play :arp [:fx [:arp :up-down 1/16]
              [:prog/epic {:inst :synth/pluck :vel 0.6 :cutoff [:lfo :tri 8 500.0 4000.0]}]])
```

The cutoff now rises and falls over eight bars. `[:ramp 300.0 6000.0 8]` would rise and then start again.

## 5. Your own parts

Name what you like so you can reuse it:

```clojure
(g/put! :my/arp [:fx [:arp :up-down 1/16] [:prog/epic {:inst :synth/pluck :vel 0.6}]])
(g/play :arp :my/arp)
(g/play :echo [:my/arp {:octave 6 :vel 0.3 :delay 0.8}])
```

Attributes at a reference win over the definition, so `:echo` is the same arpeggio an octave up, quieter and wetter.

## 6. Arrange

```clojure
(g/snap! :drop)
(g/scene! :break {:drums nil :bass nil :fill :fill/snare-roll})
(g/scene! :back {:fill nil :drums [:beat/house {:kit :kit/tr909}] :bass [:prog/epic {:inst :synth/bass :voicing :root}]})
(g/arrange! [[:break 4] [:back 8]])
```

A scene only changes the tracks it names; `nil` stops a track. `(g/fill 1)` makes `:if :fill` steps play for the next bar, and `(g/mute :pad)` / `(g/solo :drums)` work while playing.

## 7. Keep it

```clojure
(g/save! "my-track.edn")
(g/render! "my-track.wav" 16)
```

The file is plain EDN: open it, edit it, `(g/load! "my-track.edn")`, or play it from the shell with `bb play my-track.edn`.

From here, [reference.md](reference.md) lists everything a song can contain.
