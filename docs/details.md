# rawpoll details

## Why vanilla stalls

Minecraft 1.8.9 uses LWJGL 2, which reads the mouse through ordinary Windows mouse messages on the game's main thread. A 1000 Hz mouse is fine. At 4000 to 8000 Hz the main thread spends a large part of every frame working through a constant stream of mouse messages, so frames stall and the camera stutters.

## How it works

1. **Raw Input on its own thread.** rawpoll registers for Windows Raw Input on a dedicated background thread and adds up every mouse report. The camera reads the total once per frame, so no movement is dropped or aliased, whatever the polling rate.
2. **No legacy message flood while you play.** While the cursor is captured in game, rawpoll tells Windows to stop generating legacy mouse messages for Minecraft (`RIDEV_NOLEGACY`). This removes the main thread stall. Clicks, side buttons and the scroll wheel come from raw input and are passed to LWJGL directly.
3. **Normal behavior in menus.** When you open a menu, your inventory or chat, or alt-tab away, legacy mouse messages come back, so the GUI cursor works exactly as usual.

Sensitivity is unchanged: raw counts pass straight through to Minecraft's normal sensitivity math, with no acceleration and no smoothing.

## Reporting a problem

Set `debugLog=true`, reproduce the issue for about 20 seconds, then open an issue with your `logs\latest.log`, polling rate and mod list.

## Note on servers

rawpoll only changes how mouse input is read. It doesn't add aim assistance or alter what the game does with your input. Some servers restrict client mods in general, so check their rules.
