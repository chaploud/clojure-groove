# clojure-groove

Live-code grooves from your Clojure REPL. Patterns are plain EDN data: write a drum line as a step string, a melody as scale degrees, compose parts by reference, and hear every change at the next bar. Songs save to, and load from, ordinary `.edn` files.

```clojure
(require '[chaploud.groove :as g])

(g/start!)
(g/tempo 126)
(g/globals! {:root :a :scale :minor})

(g/drum :kick "x... x... x... x...")
(g/drum :clap ".... x... .... x...")
(g/drum :hat  "..x. ..x. ..x. ..xX")
(g/synth :acid [:s 0 0 4 0 2 0 6 0 0 3 0 2 7 0 4 2])
```

Everything runs on the JVM with no dependencies besides Clojure: drums and synths are synthesized in pure Clojure and played through Java Sound. A failed edit is rejected in the REPL with a pointed error, and the music keeps playing.

> Status: early. The data format is versioned (`:groove/format 2`) but may still change; see [CHANGELOG.md](CHANGELOG.md).

## Install

You need a JDK (21 or newer) and [Babashka](https://babashka.org). Babashka runs the project tasks and fetches Clojure dependencies by itself, so the Clojure CLI is optional.

| | JDK | Babashka |
|---|---|---|
| macOS | `brew install --cask temurin` | `brew install borkdude/brew/babashka` |
| Windows | `winget install EclipseAdoptium.Temurin.21.JDK` | `scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure` then `scoop install babashka` |
| Linux | `sudo apt install openjdk-21-jdk` (or your distribution's equivalent) | `curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install \| sudo bash` |

On Linux, playback goes through ALSA, which PipeWire and PulseAudio both provide. Machines without any audio output can still render WAV files.

## Try it

```sh
git clone https://github.com/chaploud/clojure-groove && cd clojure-groove
bb songs               # list the bundled songs
bb play trance         # play one (Ctrl+C stops); --device NAME picks an output, see bb devices
bb render lofi         # or render it to out/lofi.wav without touching an audio device
bb nrepl               # start an nREPL server for live coding
```

With the REPL connected, follow [docs/guide.md](docs/guide.md) (an empty bar to an arranged track in about fifteen minutes), or open [`examples/jam.clj`](examples/jam.clj) and evaluate the forms one at a time. [docs/reference.md](docs/reference.md) lists everything a song can contain.

Without Babashka, the same commands run through the Clojure CLI: `clojure -M -m chaploud.groove.cli play trance`, and `clojure -M:dev:nrepl` for the REPL.

## Use it in your project

Add it as a git dependency in `deps.edn`:

```clojure
{:deps {io.github.chaploud/clojure-groove {:git/tag "v0.1.0" :git/sha "5dbd390"}}}
```

Then `(require '[chaploud.groove :as g])` from any REPL, or play a bundled song without cloning anything:

```sh
clojure -Sdeps '{:deps {io.github.chaploud/clojure-groove {:git/tag "v0.1.0" :git/sha "5dbd390"}}}' -M -m chaploud.groove.cli play anthem
```

## Writing patterns

### Steps: the drum pad

```clojure
(g/drum :kick "x... x... x... x...")        ; x hit, X accent, . or - rest; spaces and | are ignored
(g/drum :snare [:_ :_ :_ :_ :x :_ {:vel 1 :if :fill} :x])   ; or a vector, with per-step attributes
(g/drum :kick ["x... x... x... x..." "x... x... x.x. x.x."])   ; a vector of strings: one per bar, checked
(g/drum :crash "x..............." :if [1 8])                ; a one-off: the first of every 8 loops
```

Every step is a 16th note unless the cascade sets `:step`. A pattern of 12 steps loops every 12 steps, so mixing lengths gives you polymeter; write one string per bar when you want each bar checked to be exactly one bar long. `:swing` (0–1) delays every second step by that fraction of a step; `:swing-step 1/8` swings eighths instead. In MPC terms, 58% is about `0.16` and a triplet feel (66%) about `0.33`.

Per-step attributes follow the vocabulary of hardware sequencers:

| Key | Meaning |
|---|---|
| `:vel` | velocity, 0–1 |
| `:prob` | chance to play, 0–1 |
| `:if` | condition: `:fill`, `:!fill`, `:1st`, `:!1st`, or `[a b]` (play on the a-th of every b loops) |
| `:ratchet` | repeat the hit n times within the step |
| `:nudge` | shift by a fraction of the step |
| `:humanize` | 0–1: small, repeatable random shifts of timing and velocity |
| any synth parameter | a parameter lock for that step, e.g. `{:cutoff 900.0}` |

### Notes: melodies

```clojure
(g/synth :bass [:e 0 :_ 0 :s 3 5 :e 7 :_])   ; :s :e :q :h :w (and :q. dotted, :et triplet) set the length
(g/synth :pad  [:w #{0 2 4} #{-2 0 2}])       ; a set is a chord
(g/synth :lead [1/8 0 2 1/16 4 5 :c5 :_])     ; ratios set the length; keywords like :c5 are note names
(g/synth :hook [[:e 4 4 :q 7 :e 4 2 :q 0]       ; a vector of vectors: one per bar, each checked
                [:e 5 5 :q 9 :e 7 5 :q 7]])     ; to add up to exactly one bar
```

Integers are scale degrees, resolved through `:root`, `:scale` and `:octave` from the cascade. A length applies until the next length keyword, but never outside its vector (or its bar). A note written as `{:degree 4 :glide true}` slides into its pitch from the note before it, like a 303 slide (`:glide-time` sets how long, default 0.06 s).

### Chords

Roman numerals build a chord on a degree of the current scale: uppercase is major, lowercase minor, with optional suffixes `7 maj7 6 9 add9 sus2 sus4 dim dim7 m7b5 aug`. A `b` or `#` prefix counts from the major scale instead, as in `:bVII` for a borrowed chord. Each `:prog/` part fixes the scale it is written in.

```clojure
(g/globals! {:root :d :scale :minor})
(g/synth :pad  [:w :i :VI :III :VII] :inst :synth/pad :octave 3)
(g/play  :keys [:notes {:inst :synth/keys :voicing :drop2} [:h :ii7 :V7 :w :Imaj7]])
(g/play  :arp  [:fx [:arp :up-down 1/16] [:prog/epic {:inst :synth/pluck}]])
(g/play  :bass [:fx [:struct ".x.x .x.x .x.x .x.x"] [:prog/epic {:inst :synth/bass :voicing :root}]])
(g/play  :stab [:fx [:struct "..x. ...x .... ..x."] [:prog/deep-house {:inst :synth/keys}]])
```

`:voicing` is `:close` (default), `:open`, `:drop2` or `:root`, and `:inv n` inverts; `:root` keeps only the root, in the octave nearest the key's tonic, so a bassline that follows the chords moves by step instead of leaping. `[:arp order rate]` plays each chord one note at a time (`:up :down :up-down :random`), and `[:struct rhythm]` plays whatever sounds underneath at the rhythm's hits, like Strudel's `struct`: the rhythm is a step string, one string per bar, or any node, and it contributes only its timing and the step attributes `:vel :prob :if :ratchet :nudge :gate`. Together they let one progression drive a pad, an arpeggio, stabs and a bassline. Mini-notation accepts the same numerals: `"<i VI III VII>"`.

### Cycles: mini-notation

For dense rhythms, a TidalCycles-style string fits a pattern into one bar:

```clojure
(g/mini :break "[bd ~ ~ ~] [sd ~ ~ ~] [~ ~ bd ~] [sd ~ ~ ~], hh*8?0.2")
(g/mini :melody "<0 2 4 [7 9]>(5,8)" :inst :synth/pluck)
```

Supported: `~` rest, `[ ]` subdivide, `< >` alternate per cycle, `,` layer, `*n` `/n` speed, `!n` repeat, `@n` weight, `?p` drop with probability, `(k,n,r)` Euclidean rhythm. Words are drum roles (`bd sd cp hh oh rim lt mt ht cb cr rd`, played by the current kit), scale degrees or note names.

## Parts and kits

A library of ready-made parts ships with the code, so a groove can start from a reference instead of an empty bar:

```clojure
(g/browse)                      ; what is there, by namespace
(g/browse "garage")             ; search names, tags and descriptions
(g/audition :beat/two-step)     ; hear one (on the :audition track; (g/audition nil) stops)
(g/show :beat/two-step)         ; see it as a grid
(g/describe :synth/acid)        ; an instrument's parameters and what they do

(g/play :drums [:beat/house {:kit :kit/tr909 :swing 0.1}])
(g/play :bass  [:bass/offbeat {:root :f :scale :minor}])
```

| Namespace | |
|---|---|
| `:beat/` | drum patterns: four-floor, house, deep-house, disco, techno, trance, electro, breakbeat, two-step, dnb, jungle, boom-bap, trap, dembow, afrobeat, halftime, lofi |
| `:fill/` | one-bar fills: snare-roll, toms, crash, stutter |
| `:bass/` | basslines in scale degrees: offbeat, rolling, acid, octave, root-fifth, sub, tr808, reese, funk |
| `:prog/` | chord progressions: axis, sensitive, epic, andalusian, ii-v-i, royal-road, komuro, canon, blues, dorian-vamp, deep-house |
| `:lead/` | melodies: anthem, anthem-high, pop, neon (four bars, each written over the progression named in its description), call-response, pentatonic |
| `:arp/` | arpeggios: trance, up-down, octave-bounce, thirds, and epic, axis, sensitive that follow a progression |
| `:kit/` | drum kits: default, tr808, tr909, lofi, hard |

Drum parts are written with roles (`:bd :sd :cp :hh :oh :rim :lt :mt :ht :cb :cr :rd`), and the `:kit` attribute decides which instrument plays each role, so one pattern works with every kit. Mini-notation words like `bd` are the same roles. Your own definitions win over bundled ones of the same name, and `save!` writes only yours. Define a kit of your own with `(g/kit! :my/kit {:base :kit/tr808 :bd :my/kick})`.

## Movement

Any numeric attribute can be a signal instead of a number. It is evaluated at each note's written position (before swing, nudge or humanize), counted in bars from when the track started, and repeats with its length. Signals on `:degree`, `:transpose`, `:octave` and `:midi` are rounded to whole numbers:

```clojure
(g/play :acid [:bass/acid {:cutoff [:lfo :tri 8 180.0 900.0]}])        ; sweep the filter over 8 bars
(g/play :lead [:arp/trance {:cutoff [:ramp 300.0 6000.0 8] :reverb 0.5}]) ; an 8-bar build
(g/play :pad  [:prog/epic {:pan [:lfo :sine 2 -0.6 0.6]}])               ; drift left and right
```

`[:lfo shape bars low high]` with `:sine :tri :saw :square`, and `[:ramp from to bars]`. Signals cascade like any other attribute, so one on `:globals` moves every track.

## Composing with data

A node is a Hiccup-style vector, `[tag attributes? & body]`:

| Node | |
|---|---|
| `[:steps attrs "x..."]` | step grid |
| `[:notes attrs [...]]` | melody |
| `[:cycle attrs "..."]` | mini-notation, one bar per cycle |
| `[:seq & nodes]` / `[:par & nodes]` | one after another / all together |
| `[:rep n node]` | repeat |
| `[:fx transforms node]` | `[:rev]` `[:fast n]` `[:slow n]` (whole n) `[:every n transform]` `[:transpose semitones]` `[:degrade p]` `[:arp order rate]` |
| `:clip/name` or `[:clip/name attrs]` | a reference to a definition |

Attributes cascade: they flow from `:globals` and parent nodes to their children, a definition's own attributes override what it inherits, and attributes written at a reference site override the definition.

```clojure
(g/put! :clip/motif [:notes {:inst :synth/pluck} [:s 0 2 4 7 4 2 0 :_]])
(g/play :lead [:seq :clip/motif [:fx [:transpose 5] :clip/motif]])
(g/play :echo [:clip/motif {:octave 6 :vel 0.3}])
```

References are checked before anything is adopted: an undefined name, a cycle, or a bad pattern anywhere in the tree is reported with its path, e.g. `Track :lead: Undefined reference :clip/motf (did you mean :clip/motif?) (at [:lead :seq 1 :clip/motf])`. Song files are checked the same way when they are loaded, and the error names the file.

## Live controls

Gestures you make while playing have no bang; definitions, files and the transport do.

| | |
|---|---|
| `start!` `stop!` `status` `devices` | audio transport; `(start! {:device "USB"})` picks an output, `status` lists problems reported while playing, each with how often it happened |
| `drum` `synth` `mini` `play` | set a track; edits are picked up at the next bar |
| `clear` `hush` | remove some or all tracks |
| `mute` `unmute` `solo` `unsolo` | |
| `fill` | make `:if :fill` steps play for the next n bars |
| `put!` `instrument!` `kit!` `globals!` `tempo` | definitions and the root of the cascade |
| `browse` `audition` `describe` | find, hear and inspect bundled parts, kits and instruments |
| `section!` `snap!` `launch` `arrange!` | sections (the complete set of tracks to play) and a timeline of them |
| `save!` `load!` `render!` `show` | song files, offline WAV rendering, a text grid of any node |

## Sounds

Built-in instruments are synthesized, so they need no sample downloads:

- Drums (also reachable through kit roles): `:drum/kick` `:drum/snare` `:drum/clap` `:drum/hat` `:drum/open-hat` `:drum/ride` `:drum/crash` `:drum/rim` `:drum/cowbell` `:drum/tom-low` `:drum/tom-mid` `:drum/tom-high`
- Synths: `:synth/acid` `:synth/bass` `:synth/sub` `:synth/reese` `:synth/supersaw` `:synth/pad` `:synth/pluck` `:synth/lead` `:synth/keys`

The kick ducks the bass and synth buses (sidechain). Every track has `:delay` and `:reverb` send levels (0–1) that default by bus: drums are dry with a little reverb, synths go through a tempo-synced delay and the reverb. A closed hat cuts off a ringing open hat, because both are in the `:choke :hats` group. Define your own instruments as data on top of a built-in one:

```clojure
(g/instrument! :my/bass {:base :synth/acid :cutoff 500.0 :res 0.6 :octave 1})
```

## Song files

```clojure
{:groove/format 2
 :include ["lib/house-kit.edn"]
 :globals {:tempo 138 :root :g :scale :minor}
 :instruments {...}
 :defs {:clip/kick [:steps {:inst :drum/kick} "x... x... x... x..."] ...}
 :sections {:intro {:kick :clip/kick}
            :drop {:base :intro :bass :clip/bass :roll [:fill/snare-roll {:if :fill}]}
            :break {:base :drop :kick nil}}
 :arrangement [[:intro 4] [:drop 8 {:fill 1}] [:break 4]]
 :tracks {...}}
```

A section is the complete set of tracks it plays: entering it starts them from their first step and stops every other track, and every track stops when the arrangement ends. `:base` builds on another section and `nil` drops a track inherited from it. An arrangement step `[:drop 8 {:fill 1}]` makes `:if :fill` steps play in its last bar, and `:if :1st` steps play only in a section's first loop, which is how a crash marks the start of a drop.

`:include` merges kits, instruments, definitions and sections from other files, resolved relative to the including file; the including file wins. `load!`, `bb play` and `bb render` accept a file path, a classpath resource or the name of a bundled song. The bundled songs live in [`resources/chaploud/groove/songs`](resources/chaploud/groove/songs): house, acid, techno, trance, drum and bass, lo-fi, trap, UK garage, synthwave, dub techno, an anthem built from bundled parts, polymeter, Euclidean rhythms and a walkthrough of references and the cascade.

## Development

| Task | |
|---|---|
| `bb nrepl` | nREPL with cider-nrepl and the `dev`, `test` and `examples` paths |
| `bb test` | run the test suite |
| `bb lint` / `bb fmt` / `bb fmt:check` | clj-kondo and cljfmt |
| `bb ci` | everything CI runs |
| `bb play SONG` / `bb render SONG [bars] [out.wav]` / `bb songs` / `bb devices` | the command line |
| `bb examples` | render every bundled song (smoke test) |
| `bb jar` / `bb deploy` | build the jar / deploy it to Clojars |

The design notes are in [`docs/design.md`](docs/design.md).

## License

MIT
