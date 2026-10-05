# Reference

Everything a song can contain, in one place. From the REPL, `(g/browse)` and `(g/describe k)` list the bundled parts, kits and instruments and their parameters.

## Nodes

| Node | Length | Notes |
|---|---|---|
| `[:steps attrs body]` | number of steps × `:step` (default 1/16) | body is a step string or vector, or a vector of step strings (one per bar) |
| `[:notes attrs body]` | sum of note lengths | body is a note vector, or a vector of note vectors (one per bar) |
| `[:cycle attrs "..."]` | 1 bar | TidalCycles-style mini-notation |
| `[:seq & nodes]` | sum | one after another |
| `[:par & nodes]` | longest | together; shorter children rest until the longest ends |
| `[:rep n node]` | n × node | |
| `[:fx transforms node]` | node | one transform, or a vector of them applied in order |
| `:ns/name`, `[:ns/name attrs]` | definition | a reference; `attrs` override the definition |

Attributes cascade: `:globals` < parent nodes < a node's own attributes < attributes at a reference site. Any attribute an instrument understands can appear anywhere in the cascade.

## Step bodies

| String | Vector | Meaning |
|---|---|---|
| `x` `o` | `:x` `:o` `true` `1` | hit |
| `X` | `:X` | accent (velocity 1.0) |
| `.` `-` `_` `~` | `:_` `:-` `nil` `false` `0` | rest |
| space, `\|` | | ignored |
| | `0.5` | hit with that velocity |
| | `{...}` | hit with attributes (see step attributes) |

A vector of strings is one bar per string, and each must be exactly one bar of steps.

## Note bodies

| Item | Meaning |
|---|---|
| `0` `-2` `7` | scale degree |
| `:c4` `:eb3` `:fs2` | note name (`s` or `#` for sharp, `b` for flat) |
| `:i` `:VI` `:ii7` `:Imaj7` `:bVII` | chord symbol on a degree of the scale |
| `#{0 2 4}` | chord of degrees or note names |
| `:_` `nil` | rest |
| `:w :h :q :e :s :t` | whole, half, quarter, eighth, sixteenth, thirty-second; applies to what follows |
| `[...]` as every item | one bar each; lengths reset per bar and each bar must add up to exactly one |
| `:q.` `:e.` | dotted |
| `:qt` `:et` `:st` | triplet |
| `1/8` | length as a fraction of a bar; applies to what follows |
| `{:degree 4 :vel 1.0 :glide true}` | note with attributes |

Chord suffixes: `7 maj7 6 9 add9 sus2 sus4 dim dim7 m7b5 aug`. Uppercase numerals are major, lowercase minor. A `b`/`#` prefix counts from the major scale.

## Mini-notation

`~` or `-` rest, `[a b]` subdivide, `<a b>` one per cycle, `a, b` layer, `a*2` faster, `a/2` slower, `a!3` repeat, `a@3` weight, `a?` or `a?0.3` drop by chance, `a(3,8,2)` Euclidean rhythm (Bjorklund) with rotation. Words: drum roles (`bd sd cp hh oh rim lt mt ht cb cr rd`, also `kick snare clap hat ch rs ride crash`), degrees, note names and chord symbols.

## Transforms

| Transform | |
|---|---|
| `[:rev]` | reverse |
| `[:fast n]` / `[:slow n]` | play n times per loop / one nth per loop (whole n) |
| `[:every n transform]` | apply on every nth loop, starting with the first |
| `[:transpose n]` | semitones |
| `[:degrade p]` | drop events with probability p (deterministic: the same loop drops the same events) |
| `[:arp order rate]` | play chords one note at a time; order `:up :down :up-down :random`, rate default 1/16 |
| `[:struct rhythm]` | play whatever sounds underneath at the rhythm's hits; rhythm is a step string, a vector of step strings (one per bar), or a node, and it contributes only its timing and `:vel :prob :if :ratchet :nudge :gate` |

## Attributes

Pitch: `:root` (note name, default `:c`), `:scale` (keyword or a vector of semitones), `:octave` (default from the instrument), `:degree`, `:note`, `:midi`, `:transpose`, `:voicing` (`:close :open :drop2 :root`; `:root` is the root nearest the tonic), `:inv`.

Timing and feel: `:step`, `:swing` (0–1 of a step), `:swing-step`, `:nudge` (fraction of the note), `:humanize` (0–1), `:gate` (fraction of the note held), `:glide`, `:glide-time`.

Steps: `:vel`, `:prob`, `:if` (`:fill :!fill :1st :!1st [a b]`), `:ratchet`. `:1st` counts from when the track started, which a section resets, so `[:fill/crash {:if :1st}]` marks a section's first bar; `[a b]` plays on the a-th of every b loops, e.g. `[1 8]` for once every eight.

Sound: `:inst`, `:kit`, and any instrument parameter, for example `:cutoff`, `:res`, `:hp`, `:decay`, `:pan`, `:delay`, `:reverb`. `(g/describe :synth/acid)` lists an instrument's parameters with their meaning.

Signals: any numeric attribute can be `[:lfo shape bars low high]` (`:sine :tri :saw :square`) or `[:ramp from to bars]`, evaluated at each note's written position in bars since its track started.

Globals only: `:tempo` (20–999 BPM).

## Instruments

| Drums | Synths |
|---|---|
| `:drum/kick` `:drum/snare` `:drum/clap` `:drum/hat` `:drum/open-hat` `:drum/ride` `:drum/crash` `:drum/rim` `:drum/cowbell` `:drum/tom-low` `:drum/tom-mid` `:drum/tom-high` | `:synth/acid` `:synth/bass` `:synth/sub` `:synth/reese` `:synth/supersaw` `:synth/pad` `:synth/pluck` `:synth/lead` `:synth/keys` `:synth/speaker` |

Define your own with `:base`: `{:my/bass {:base :synth/acid :cutoff 500.0}}`. Kits map roles to instruments: `{:my/kit {:base :kit/tr808 :bd :my/kick}}`.

## Song files

```clojure
{:groove/format 2
 :include ["relative/path.edn"]   ; merges :kits :instruments :defs :sections; this file wins
 :globals {...}
 :kits {...}
 :instruments {...}
 :defs {...}
 :sections {:name {:track node, :base :other-section, :dropped-track nil}}
 :arrangement [[:section bars] [:section bars {:fill n}] ...]
 :tracks {:track node}}
```

A section is the complete set of tracks it plays. Entering it, at an arrangement step or with `launch`, starts those tracks from their first step and stops every other track; this happens at every step, even when the same section repeats. `:base` builds on another section, and `nil` drops a track inherited from it. `{:fill n}` makes `:if :fill` steps play in the step's last n bars, and every track stops when the arrangement ends. A song file may contain only the keys above; anything else is rejected, so a misspelled key cannot silently do nothing. Files from 0.1.0 used `:scenes`, which only changed the tracks they named; loading one explains how to convert it.

## REPL

| | |
|---|---|
| `start!` `stop!` `status` `devices` | transport; `(start! {:device "name" :buffer-ms 170})` |
| `drum` `synth` `mini` `play` `clear` `hush` | tracks |
| `mute` `unmute` `solo` `unsolo` `fill` | performance |
| `put!` `instrument!` `kit!` `globals!` `tempo` | definitions |
| `section!` `snap!` `launch` `arrange!` | sections |
| `save!` `load!` `render!` | files |
| `browse` `audition` `describe` `show` `session` | discovery |

## Command line

`bb play SONG [--bars N] [--device NAME]`, `bb render SONG [bars] [out.wav]`, `bb songs`, `bb devices`, or `clojure -M -m chaploud.groove.cli ...` without Babashka.
