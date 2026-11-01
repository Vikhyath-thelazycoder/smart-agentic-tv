# Supported boards

One board is tested so far. Tell us about yours with a [board report](../../../issues/new?template=board-report.yml), even if it didn't work.

| Board | Chip | Android | Firmware | Status | Profile |
|---|---|---|---|---|---|
| U.11 universal board | HiSilicon Hi3751V350 | 9 (it claims 12) | KitKing | **Tested on one TV** | `u11-kitking.properties` |

## What works on the tested board

Home screen, phone remote, mouse, typing, Source button, volume, opening apps, voice commands and Jarvis.

Tested from a computer and an emulator phone connected to that TV: search, timers, reminders, routines, lowering the TV's sound, and closing the debugging port.

**Not yet confirmed on a real phone:** spoken replies and interrupting Jarvis by talking over it.

## Other boards

The app should install on Android 8 or newer, but see the known issue below. Without a profile, these features stay off: sending the old launcher back, the touchpad mouse, the Source button, and the board's own settings and Miracast shortcuts.

The original Free My TV also supports Google TV and Android TV sets through its home-screen switch. That code is still here, but this project hasn't tested it.

## Known issue: newer Android

The key helper is tested on Android 9. **On an Android 14 emulator it stops at startup**, because Android changed a call it uses. Remote keys, mouse and typing then don't work, and the phone stays on "keys starting". The other features don't need the helper. Android versions in between are untested, so please report what you find.

## What can differ

| Part | Why it can fail |
|---|---|
| Sending keys | Needs ADB running on the TV |
| Closing the debugging port | Needs ADB root, which many boards don't allow |
| Mouse and Source | Input device names and codes differ per board |
| Streaming apps | Some refuse uncertified boards. Netflix did on the tested board |
| TV voice | Needs Google's speech engine (`com.google.android.tts`) |
