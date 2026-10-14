# rawpoll for Minecraft 1.8.9

**Any polling rate support for Minecraft 1.8.9 (Forge).** Use your mouse at any polling rate without the camera jitter and FPS collapse that vanilla 1.8.9 gets above 1000 Hz.

Forge 1.8.9, Windows, client side only.

## The problem

Minecraft 1.8.9 uses LWJGL 2, which reads the mouse through ordinary Windows mouse messages on the game's main thread. A 1000 Hz mouse is fine. At 4000 to 8000 Hz the main thread spends a large part of every frame working through a constant stream of mouse messages, so frames stall and the camera stutters.

Measured on the same PC while flicking at 8000 Hz:

| | Vanilla 1.8.9 input | rawpoll |
| --- | --- | --- |
| FPS while moving the mouse | 42 to 65 | 510 to 1200 |
| Worst main thread stall per second | 50 to 110 ms | 2 to 11 ms |
| Worst frame time per second | 60 to 195 ms | 4 to 12 ms |

## How it works

1. **Raw Input on its own thread.** rawpoll registers for Windows Raw Input on a dedicated background thread and adds up every mouse report. The camera reads the total once per frame, so no movement is dropped or aliased, whatever the polling rate.
2. **No legacy message flood while you play.** While the cursor is captured in game, rawpoll tells Windows to stop generating legacy mouse messages for Minecraft (`RIDEV_NOLEGACY`). This removes the main thread stall. Clicks, side buttons and the scroll wheel come from raw input and are passed to LWJGL directly.
3. **Normal behavior in menus.** When you open a menu, your inventory or chat, or alt-tab away, legacy mouse messages come back, so the GUI cursor works exactly as usual.

Sensitivity is unchanged: raw counts pass straight through to Minecraft's normal sensitivity math, with no acceleration and no smoothing.

## Install

1. Install [Forge 1.8.9](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.8.9.html) (tested on 11.15.1.2318).
2. Download `rawpoll-1.0.0.jar` from [Releases](https://github.com/as9pa/rawpoll/releases).
3. Put it in `%APPDATA%\.minecraft\mods\`.
4. Launch the Forge profile. `logs\latest.log` should contain `Raw input is active`.

## Compatibility

- **Windows only.** On other systems the mod does nothing and vanilla input stays in place.
- **Forge only.** Lunar, Badlion and other clients that don't load Forge mods can't use it.
- Works with OptiFine.
- If another mod replaces Minecraft's `MouseHelper`, rawpoll logs which one and installs itself again.
- Tested at 1000, 2000, 4000 and 8000 Hz.

## Config

`config\rawpoll.cfg`:

| Option | Default | What it does |
| --- | --- | --- |
| `noLegacyWhileGrabbed` | `true` | Turns off legacy mouse messages while in game. Set to `false` to keep only the raw input camera, if a mod that needs legacy mouse events misbehaves. Expect stalls to return at high polling rates. |
| `debugLog` | `false` | Logs FPS, frame timing, raw events per second and stall times once per second while in game. Useful for bug reports. |

## Reporting a problem

Set `debugLog=true`, reproduce the issue for about 20 seconds, then open an issue with your `logs\latest.log`, polling rate and mod list.

## Building

Requires a JDK 17 or newer to run Gradle. Gradle downloads a Java 8 toolchain for compiling if you don't have one.

```
gradlew build
```

The jar is written to `build\libs\`.

## Note on servers

rawpoll only changes how mouse input is read. It doesn't add aim assistance or alter what the game does with your input. Some servers restrict client mods in general, so check their rules.

## License

MIT
