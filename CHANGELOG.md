# Changelog

## Unreleased

- Sections replace scenes. A section is the complete set of tracks it plays: entering it restarts them and stops the rest, `:base` builds on another section and `nil` drops an inherited track. Song files use `:sections` and `:groove/format 2`; files with `:scenes` are rejected with a message explaining the change. `scene!` is now `section!`.
- Arrangement steps take options: `[:build 8 {:fill 1}]` turns on `:if :fill` steps in the step's last bar, so fills no longer need their own section.
- `[:struct rhythm]` plays the notes underneath at a rhythm's hits, so stabs and basslines can follow a progression without writing every bar out.
- Step and note bodies can be written one bar at a time (a vector of strings, or a vector of note vectors); each bar is checked to be exactly one bar long.
- New parts: `:lead/` melodies and `:arp/` arpeggios. The bundled songs are rewritten as full arrangements with melodies, builds, fills and second drops.

## 0.1.0 (2026-10-05)

First release.

- Patterns as EDN data: step strings, note vectors with scale degrees and chord symbols, and TidalCycles-style mini-notation, composed with `:seq`, `:par`, `:rep`, `:fx` and qualified-keyword references, with an attribute cascade.
- Live sessions from the REPL: every change is validated before it is adopted and heard at the next bar; scenes, arrangements, fills, mute and solo; songs saved to and loaded from EDN files with `:include`.
- Pure-JVM synthesis: twelve drum voices and nine synths, kick ducking, per-track delay and reverb sends, choke groups, slides, realtime or offline WAV rendering.
- Feel and movement: swing, humanize, conditional and probabilistic steps, ratchets, arpeggios, voicings, and LFO or ramp signals on any numeric attribute.
- A bundled library of beats, fills, basslines, chord progressions and kits, with `browse`, `audition` and `describe`, plus fourteen example songs.
- A command line (`bb play`, `bb render`, `bb songs`, `bb devices`) that runs on macOS, Windows and Linux with a JDK and Babashka.
