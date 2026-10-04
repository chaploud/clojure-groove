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
(g/synth :acid [:s 0 0 7 0 3 0 10 0 0 5 0 3 12 0 7 3])
```

Everything runs on the JVM with no dependencies besides Clojure: drums and synths are synthesized in pure Clojure and played through Java Sound. A failed edit is rejected in the REPL with a pointed error, and the music keeps playing.

> Status: early. The data format is versioned (`:groove/format 1`) but may still change.

## Requirements

- JDK 21+ (tested on JDK 25) and the Clojure CLI
- [Babashka](https://babashka.org) for the project tasks

## Quick start

```sh
bb nrepl                       # start an nREPL server with cider-nrepl
bb render examples/trance.edn  # render a song to out/trance.wav without opening an audio device
```

Then open [`examples/jam.clj`](examples/jam.clj) and evaluate the forms one at a time.

## Writing patterns

### Steps: the drum pad

```clojure
(g/drum :kick "x... x... x... x...")        ; x hit, X accent, . or - rest; spaces and | are ignored
(g/drum :snare [:_ :_ :_ :_ :x :_ {:vel 1 :if :fill} :x])   ; or a vector, with per-step attributes
```

Every step is a 16th note unless the cascade sets `:step`. A pattern of 12 steps loops every 12 steps, so mixing lengths gives you polymeter.

Per-step attributes follow the vocabulary of hardware sequencers:

| Key | Meaning |
|---|---|
| `:vel` | velocity, 0–1 |
| `:prob` | chance to play, 0–1 |
| `:if` | condition: `:fill`, `:!fill`, `:1st`, `:!1st`, or `[a b]` (play on the a-th of every b loops) |
| `:ratchet` | repeat the hit n times within the step |
| `:nudge` | shift by a fraction of the step |
| any synth parameter | a parameter lock for that step, e.g. `{:cutoff 900.0}` |

### Notes: melodies

```clojure
(g/synth :bass [:e 0 :_ 0 :s 3 5 :e 7 :_])   ; :s :e :q :h :w (and :q. dotted, :e3 triplet) set the length
(g/synth :pad  [:w #{0 2 4} #{-2 0 2}])       ; a set is a chord
(g/synth :lead [1/8 0 2 1/16 4 5 :c5 :_])     ; ratios set the length; keywords like :c5 are note names
```

Integers are scale degrees, resolved through `:root`, `:scale` and `:octave` from the cascade. A length applies until the next length keyword, but never outside its vector.

### Cycles: mini-notation

For dense rhythms, a TidalCycles-style string fits a pattern into one bar:

```clojure
(g/mini :break "[bd ~ ~ ~] [~ ~ sd ~] [~ ~ bd bd] [~ ~ sd ~], hh*8?0.2")
(g/mini :melody "<0 2 4 [7 9]>(5,8)" :inst :synth/pluck)
```

Supported: `~` rest, `[ ]` subdivide, `< >` alternate per cycle, `,` layer, `*n` `/n` speed, `!n` repeat, `@n` weight, `?p` drop with probability, `(k,n,r)` Euclidean rhythm. Words are drum names (`bd sd cp hh oh rim lt mt ht cb cr rd`), scale degrees or note names.

## Composing with data

A node is a Hiccup-style vector, `[tag attributes? & body]`:

| Node | |
|---|---|
| `[:steps attrs "x..."]` | step grid |
| `[:notes attrs [...]]` | melody |
| `[:cycle attrs "..."]` | mini-notation, one bar per cycle |
| `[:seq & nodes]` / `[:par & nodes]` | one after another / all together |
| `[:rep n node]` | repeat |
| `[:fx transforms node]` | `[:rev]` `[:fast n]` `[:slow n]` `[:every n transform]` `[:transpose semitones]` `[:degrade p]` |
| `:clip/name` or `[:clip/name attrs]` | a reference to a definition |

Attributes cascade: they flow from `:globals` and parent nodes to their children, a definition's own attributes override what it inherits, and attributes written at a reference site override the definition.

```clojure
(g/put! :clip/motif [:notes {:inst :synth/pluck} [:s 0 2 4 7 4 2 0 :_]])
(g/play! :lead [:seq :clip/motif [:fx [:transpose 5] :clip/motif]])
(g/play! :echo [:clip/motif {:octave 6 :vel 0.3}])
```

References are checked before anything is adopted: an undefined name, a cycle, or a bad pattern anywhere in the tree is reported with its path, e.g. `Track :lead: Undefined reference :clip/motf` at `[:lead :seq 1 :clip/motf]`.

## Live controls

| | |
|---|---|
| `start!` `stop!` | audio transport |
| `drum` `synth` `mini` `play!` | set a track; edits are picked up at the next bar |
| `clear!` `hush` | remove some or all tracks |
| `mute` `unmute` `solo` `unsolo` | |
| `fill!` | make `:if :fill` steps play for the next n bars |
| `put!` `instrument!` `globals!` `tempo` | definitions and the root of the cascade |
| `scene!` `snap!` `launch!` `arrange!` | scenes (track → node maps) and a scene timeline |
| `save!` `load!` `render!` `show` | song files, offline WAV rendering, a text grid of any node |

## Sounds

Built-in instruments are synthesized, so they need no sample downloads:

- Drums: `:drum/kick` `:drum/snare` `:drum/clap` `:drum/hat` `:drum/open-hat` `:drum/ride` `:drum/crash` `:drum/rim` `:drum/cowbell` `:drum/tom-low` `:drum/tom-mid` `:drum/tom-high`
- Synths: `:synth/acid` `:synth/bass` `:synth/sub` `:synth/reese` `:synth/supersaw` `:synth/pad` `:synth/pluck` `:synth/lead` `:synth/keys`

The kick ducks the bass and synth buses (sidechain), and synths go through a tempo-synced delay and a small reverb. Define your own instruments as data on top of a built-in one:

```clojure
(g/instrument! :my/bass {:base :synth/acid :cutoff 500.0 :res 0.6 :octave 1})
```

## Song files

```clojure
{:groove/format 1
 :include ["lib/house-kit.edn"]
 :globals {:tempo 138 :root :g :scale :minor}
 :instruments {...}
 :defs {:clip/kick [:steps {:inst :drum/kick} "x... x... x... x..."] ...}
 :scenes {:intro {:kick :clip/kick} :drop {...}}
 :arrangement [[:intro 4] [:drop 8]]
 :tracks {...}}
```

`:include` merges instruments, definitions and scenes from other files; the including file wins. See [`examples/`](examples) for house, acid, techno, trance, drum and bass, lo-fi, trap, polymeter and Euclidean examples.

## Development

| Task | |
|---|---|
| `bb nrepl` | nREPL with cider-nrepl and the `dev`, `test` and `examples` paths |
| `bb test` | run the test suite |
| `bb lint` / `bb fmt` / `bb fmt:check` | clj-kondo and cljfmt |
| `bb ci` | everything CI runs |
| `bb render FILE [bars] [out.wav]` / `bb examples` | offline rendering |

The design notes are in [`docs/design.md`](docs/design.md).

## License

MIT
