# Changelog

## 0.1.0 (2026-10-05)

First release.

- Patterns as EDN data: step strings, note vectors with scale degrees and chord symbols, and TidalCycles-style mini-notation, composed with `:seq`, `:par`, `:rep`, `:fx` and qualified-keyword references, with an attribute cascade.
- Live sessions from the REPL: every change is validated before it is adopted and heard at the next bar; scenes, arrangements, fills, mute and solo; songs saved to and loaded from EDN files with `:include`.
- Pure-JVM synthesis: twelve drum voices and nine synths, kick ducking, per-track delay and reverb sends, choke groups, slides, realtime or offline WAV rendering.
- Feel and movement: swing, humanize, conditional and probabilistic steps, ratchets, arpeggios, voicings, and LFO or ramp signals on any numeric attribute.
- A bundled library of beats, fills, basslines, chord progressions and kits, with `browse`, `audition` and `describe`, plus fourteen example songs.
- A command line (`bb play`, `bb render`, `bb songs`, `bb devices`) that runs on macOS, Windows and Linux with a JDK and Babashka.
