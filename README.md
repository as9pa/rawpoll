# rawpoll for Minecraft 1.8.9

**Any polling rate support for Minecraft 1.8.9 (Forge).** Use your mouse at any polling rate without the camera jitter and FPS collapse that vanilla 1.8.9 gets above 1000 Hz.

**[Download](https://github.com/as9pa/rawpoll/releases/latest)** (Forge 1.8.9, Windows, client side only)

## What it does

- **Raw Input thread:** reads every mouse report on its own thread, so no movement is dropped at any polling rate.
- **No message flood:** stops legacy mouse messages while you play, which removes the main thread stall.
- **Normal menus:** legacy messages return in menus, inventory, chat and when you alt-tab.
- **Same feel:** raw counts go straight to Minecraft's sensitivity, no acceleration, no smoothing.
- **Buttons and scroll:** clicks, side buttons and the wheel work as usual.
- **Mod friendly:** works with OptiFine, and reinstalls itself if another mod replaces `MouseHelper`.

Measured on the same PC while flicking at 8000 Hz:

| | Vanilla 1.8.9 input | rawpoll |
| --- | --- | --- |
| FPS while moving the mouse | 42 to 65 | 510 to 1200 |
| Worst main thread stall per second | 50 to 110 ms | 2 to 11 ms |
| Worst frame time per second | 60 to 195 ms | 4 to 12 ms |

## Install

1. Install [Forge 1.8.9](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.8.9.html) (tested on 11.15.1.2318).
2. Put `rawpoll-1.0.0.jar` in `%APPDATA%\.minecraft\mods\`.
3. Launch the Forge profile.

## Compatibility

- Windows only. Elsewhere the mod does nothing and vanilla input stays.
- Requires Forge 1.8.9. Lunar Client (now including Badlion) can load Forge mods; rawpoll has not been tested there yet.
- Tested at 1000, 2000, 4000 and 8000 Hz.

## Config

`config\rawpoll.cfg`:

- `noLegacyWhileGrabbed` (default `true`): set `false` if a mod needs legacy mouse events. Stalls return at high polling rates.
- `debugLog` (default `false`): logs FPS, frame timing and stall times once per second.

More: [details, reporting a problem, servers](docs/details.md)

## Building

Needs a JDK 17 or newer to run Gradle (it fetches a Java 8 toolchain). Run `gradlew build`; the jar lands in `build\libs\`.

## License

MIT
