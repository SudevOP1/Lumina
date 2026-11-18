# Beat detection test bench

Run from `beat_detection/`:

```
java TestCustom.java "path_to_file.mp3"   # KickDetector (the app's)
java TestKotlin.java "path_to_file.mp3"   # kotlin_test/AudioBeatDetector.kt
java TestAubio.java "path_to_file.mp3"    # aubio's beat tracker (aubio_tempo / aubiotrack)
```

`TestKotlin` downloads the Kotlin compiler into `lib/kotlin/` on first run.
`TestAubio` downloads the official aubio 0.4.6 win64 build into `lib/aubio/` on first run
(other OS: set `AUBIO_LIB` to an installed libaubio).

## Controls

| Input       | Action                             |
| ----------- | ---------------------------------- |
| `Space`     | Play / pause                       |
| Left click  | Seek to the clicked time           |
| Right click | Drag timeline                      |
| Mouse wheel | Zoom in / out                      |
| `←` / `→`   | Seek ±5s                           |
| `↑` / `↓`   | Sensitivity ±5 (re-runs detection) |
