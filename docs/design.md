# Design notes

## Principles

- **One data model, several notations.** Step strings, note vectors and mini-notation all produce the same event stream, and everything the REPL does is a change to plain data. Notations are an ease-of-use layer over a simple core; they never fork the meaning.
- **Low floor first, terseness second.** The first ten minutes need three verbs (`drum`, `synth`, `tempo`) and a step string. Mini-notation and transforms are there when density matters, not on the default path.
- **Borrow vocabulary, don't invent it.** Step conditions, probability, ratchets and parameter locks use the names of hardware sequencers; sections follow clip launchers (a section, like an Ableton scene with stop buttons, is the complete state) and launch at the next bar; mini-notation follows TidalCycles, including Bjorklund Euclidean rhythms.
- **Validate before adopting.** Every change is expanded and dry-run before it replaces what is playing: every event a track can produce over its first loops (ignoring chance and conditions) must resolve to parameters that build a voice, and every section and arrangement step is checked the same way. An error goes back to the REPL with the path to the offending node. Whatever still fails at play time is isolated to one track or one voice, reported once, and a non-finite sample resets the effect buffers instead of silencing the mix.
- **Data over functions over macros.** Transforms are vectors such as `[:every 4 [:rev]]`, not closures, so songs can be saved, diffed and loaded.

## Model

```
song EDN ──read/include──▶ session ──expand (refs + cascade)──▶ tree ──query bar n──▶ events ──▶ voices
                              ▲
REPL: drum / synth / play / put! / section! … (each a pure session → session function)
```

- **Session** (`chaploud.groove.session`): globals, kits, instruments, defs, sections, tracks, arrangement, mute/solo/fill. Every REPL call is a pure function on it; `live/commit!` swaps it in only after `validate!` succeeds.
- **Expansion** (`chaploud.groove.expand`): resolves qualified-keyword references, detects cycles, parses notations and merges attributes. The merge order is parent context < node attributes < reference-site overrides, and overrides keep winning below the reference.
- **Query** (`chaploud.groove.query`): pure function from an expanded tree, a bar and the loop iteration to events with rational times. Loop iterations drive `:every`, `<alternation>`, step conditions and seeded randomness, so rendering is deterministic.
- **Pitch** (`chaploud.groove.pitch`): `:midi` > `:note` > `:degree`, the most specific key wins, in the spirit of SuperCollider's default event.
- **Engine** (`chaploud.groove.engine.*`): procedural voices (sine-sweep kick, filtered-noise snare and clap, six-square metallic cymbals, polyBLEP oscillators with a TPT state-variable filter), a drum bus and a bus for bass and synths that the kick ducks, per-voice sends to a tempo-synced delay and a Freeverb-style reverb. The mixer renders 256-frame blocks either into a `SourceDataLine` or into a buffer for offline rendering.
- **Transport** (`chaploud.groove.live`): one thread schedules each bar about 120 ms ahead, sample-accurately, from a consistent snapshot of the session. New tracks start at the next bar; edits to existing tracks keep their phase.

## Alternatives not taken

| Alternative | Why not |
|---|---|
| Patterns as functions (Strudel, mu) | Cannot be saved as EDN or compared |
| Holding vars for hot reload | A namespace refresh leaves the old var playing; a keyword registry has no such problem |
| Tagged literals such as `#groove/steps "x..."` | Every reader of a song file would need the reader functions |
| State that carries across the file (MML's `l8` `o4`) | A fragment cannot be read on its own; lengths here reset at each vector |
| Relative pitch chains (LilyPond `\relative`) | Editing one note shifts every later note |
| Whitespace as time (ixi lang) | Editors reformat whitespace |
| Malli schemas for the tree | Hand-written expansion already reports precise paths; revisit if the format grows |
| Sections as diffs of the previous state (0.1.0 scenes) | A forgotten `nil` kept tracks playing, and a section's sound depended on everything before it |
| Relative degrees inside `[:struct]` (Tidal's `\|+`) | One transform with two meanings; `:struct` takes only timing, pitches stay with the notes |
| Sample playback, SF2 and MIDI output | Not needed for the first experience; the event stream is backend-neutral |
