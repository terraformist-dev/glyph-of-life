# Glyph Life (repo glyph-life): notes for Claude

Owner has no coding experience and works only from a phone. Explain changes in plain English,
never ask them to run commands, and always finish by pushing to `main` and confirming the
GitHub Actions build went green (the build publishes `glyph-life.apk` as the latest release).

## Project

Android app (Kotlin, no AndroidX, no Compose, UI built in code), package `dev.terraformist.glyphlife`,
minSdk 34, target Phone (3). Builds only in GitHub Actions (`.github/workflows/build.yml`);
the sandbox cannot reach Google's Maven or Gradle servers. Set up the same way as the owner's
Glyph Weather (`terraformist-dev/glyph-weather`) and Dotchi (`terraformist-dev/dotchi`) apps:
borrow proven patterns from there.

The debug build is signed with `app/glyphlife.keystore` (committed on purpose) so updates
install over the old app. Don't change the key or applicationId.

## What it does (agreed with the owner from an interactive sketch)

Conway's Game of Life (B3/S23) on the 489-dot disc (`Disc`, real row widths). Cells outside the
disc are permanently dead: the edge is a wall, no wrap-around.

- Seed styles (`SeedStyle`): SOUP (34% random), MANDALA (random eighth of a radius 8-11 disc,
  density 25-40%, mirrored 8 ways; up to 40 candidates are simulated ahead and the first lasting
  80 generations, else the longest-lived, is used, because symmetric starts often die within ~10), CLASSICS (R-pentomino, acorn, diehard, pi- and B-heptomino,
  thunderbird; random rotation/flip, centred).
- Long-press (`GlyphToy.EVENT_CHANGE`): next style + reseed. Shake (3 jolts > 2.2 g in 1 s): reseed.
- Settled = population < 3, or a state repeats within the last 60 generations three times running,
  or 1,500 generations have passed.
  Hold 2.5 s, then reseed with the ripple.
- Look: afterglow (newborns rise 40%/16 ms, dying cells keep 88%/16 ms) and a ripple reveal from
  the centre on each new seed (`Glow`). Speed: slow 500 / medium 200 (default) / fast 100 ms per generation.
- Style and speed are saved in `Settings` and shared by the app and the toy.

## Glyph Matrix SDK 2.0 (verified in Dotchi)

- `GlyphMatrixManager.getInstance(ctx)`, `init(Callback)`, `register(Glyph.DEVICE_23112)`,
  `setMatrixFrame(IntArray)`, `turnOff()`, `unInit()`. Frames: 625 ints, 0..4095 per LED.
- `GlyphToy` events: `EVENT_CHANGE` (long press), `EVENT_AOD` (once a minute when always-on),
  `EVENT_ACTION_DOWN`/`EVENT_ACTION_UP`. A quick press switches toys, so it can't be used.
- Toy service lives only while selected; return `Messenger.binder` from `onBind`.

## Power

Awake (50 ms frames, partial wake lock, accelerometer on) for 60 s after pick / long-press /
button touch / shake, and while the screen is on. Resting: one still frame, accelerometer off,
significant-motion trigger armed; when it fires we listen for a shake for 8 s, then rest again.
Always-on tick: one generation per minute, still frame, live cells at 60% (153), just-died at 30.
