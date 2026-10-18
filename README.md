# Glyph of Life

Conway's Game of Life on the round Glyph Matrix on the back of the Nothing Phone (3).

- **Shake** the phone for a new colony.
- **Long-press** the Glyph Button for the next seed style:
  - **Random soup**: about a third of the dots switched on at random.
  - **Mandala**: a random pattern mirrored eight ways, so it stays perfectly symmetrical as it grows.
  - **Classics**: one famous small pattern (R-pentomino, acorn, diehard and others) dropped in the middle.
- When a colony dies out or gets stuck in a loop, it pauses for a moment and a new one starts by itself.
- The edge of the matrix is a wall: nothing lives beyond it.
- With always-on, the colony moves on one generation a minute, a little dimmer, with a faint trace of what just died. Touch the Glyph Button to wake it for a minute at full speed.

The app shows the same thing live, and is where you choose the seed style and the speed (slow, medium, fast).

## Getting the app onto your phone

1. On your phone, open **Releases → Latest** on this repo's GitHub page.
2. Tap **glyph-life.apk** to download it.
3. Open the download and tap **Install** (allow your browser to install apps the first time).
4. Go to **Settings › Glyph Interface › Glyph Toys** and add **Glyph Life**.

New builds install over the old one.

## How it works

| File | What it does |
|---|---|
| `Life.kt` | The round matrix (489 dots), the Life rules, the three seed styles, spotting when a colony has settled, and the fading trails. |
| `LifeToyService.kt` | The Glyph Toy: shake, long-press, always-on, and saving battery while resting. |
| `Settings.kt` | The chosen seed style and speed, and shake detection. |
| `MainActivity.kt` | The app screen: a live colony, seed style and speed. |
| `DotPainter.kt` | Paints the dots for the app. |

The Glyph Matrix SDK (`app/libs/glyph-matrix-sdk-2.0.aar`) is from
[Nothing-Developer-Programme/GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit) (MIT).
