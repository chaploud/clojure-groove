# Changelog

## Unreleased

- Realtime playback renders 170 ms ahead by default (was 43 ms) and warms the JIT before the first block, so a busy CPU or a garbage-collection pause no longer clicks; `bb play` runs on ZGC. `(start! {:buffer-ms n})` changes the buffer, and `status` reports dropouts if they still happen.
- Sections replace scenes. A section is the complete set of tracks it plays: entering it restarts them and stops the rest, `:base` builds on another section and `nil` drops an inherited track. Song files use `:sections` and `:groove/format 2`; files with `:scenes` are rejected with a message explaining the change. `scene!` is now `section!`.
- Arrangement steps take options: `[:build 8 {:fill 1}]` turns on `:if :fill` steps in the step's last bar, so fills no longer need their own section.
- `[:struct rhythm]` plays the notes underneath at a rhythm's hits, so stabs and basslines can follow a progression without writing every bar out. The rhythm contributes only its timing and step attributes.
- `:voicing :root` picks the root in the octave nearest the tonic, so basslines that follow a progression move by step.
- An arrangement now stops every track when it ends, instead of repeating its last section.
- Song files with unknown or misspelled keys, or a non-map where a map belongs, are rejected; validation errors from a file name the file, and every expansion error includes the path to the node.
- `:prob`, `:delay`, `:reverb`, `:bus` and `:choke` are checked before a change is adopted, not when it plays. `status` counts how often each problem recurs, and output that goes non-finite is reported.
- Step and note bodies can be written one bar at a time (a vector of strings, or a vector of note vectors); each bar is checked to be exactly one bar long.
- `:hp` adds a highpass after a synth's filter, and `:synth/speaker` uses it to sound like a small in-store speaker; ramping `:hp` down opens it up to full range.
- Reggae: `:beat/one-drop`, and `:synth/organ` (bubble), `:synth/skank` (offbeat guitar chop) and `:synth/melodica`. The `:delay-feedback` global sets how long the delay keeps repeating.
- New parts: `:lead/` melodies and `:arp/` arpeggios. A 30-second `showcase` song goes from a filtered intro through a build into a full drop. The bundled songs are rewritten as full arrangements with melodies, builds, fills and second drops.

## 0.1.0 (2026-10-05)

First release.

- Patterns as EDN data: step strings, note vectors with scale degrees and chord symbols, and TidalCycles-style mini-notation, composed with `:seq`, `:par`, `:rep`, `:fx` and qualified-keyword references, with an attribute cascade.
- Live sessions from the REPL: every change is validated before it is adopted and heard at the next bar; scenes, arrangements, fills, mute and solo; songs saved to and loaded from EDN files with `:include`.
- Pure-JVM synthesis: twelve drum voices and nine synths, kick ducking, per-track delay and reverb sends, choke groups, slides, realtime or offline WAV rendering.
- Feel and movement: swing, humanize, conditional and probabilistic steps, ratchets, arpeggios, voicings, and LFO or ramp signals on any numeric attribute.
- A bundled library of beats, fills, basslines, chord progressions and kits, with `browse`, `audition` and `describe`, plus fourteen example songs.
- A command line (`bb play`, `bb render`, `bb songs`, `bb devices`) that runs on macOS, Windows and Linux with a JDK and Babashka.
