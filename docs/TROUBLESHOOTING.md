# Troubleshooting

| Problem | Try this |
|---|---|
| `adb connect` fails | Check the IP and that USB debugging is on. If the debugging port was closed from the app, open it again from the phone menu |
| "device offline" | `adb kill-server`, then connect again |
| macOS says `No route to host` though the TV answers | Allow your terminal under Privacy & Security > Local Network |
| Phone says "keys starting" for long | Wait a minute. The helper needs ADB running on the TV, so leave USB debugging on. On newer Android it may not start at all: see the [known issue](SUPPORTED-BOARDS.md#known-issue-newer-android) |
| Phone can't find the TV | Same Wi-Fi? Some routers block devices from seeing each other (client or AP isolation) |
| Old launcher comes back | Your board needs a [profile](ADD-YOUR-BOARD.md) with `old_home` |
| Mouse or Source does nothing | Your board needs those values in its [profile](ADD-YOUR-BOARD.md) |
| "I need an AI key first" | Add a [Gemini or Groq key](API-KEYS.md) |
| Shows finds nothing | Add a [TMDB key](API-KEYS.md) and set your country |
| A title only opens the app | That app gives no link for it. See the table in the [README](../README.md#what-each-app-allows) |
| Install link stopped working | The TV's address changed. **Settings > Phone** on the TV shows the current link |
| Debugging port says `problem` | Your board didn't allow closing it. See [SECURITY.md](../SECURITY.md) |
| Home screen keeps crashing | After 3 crashes in 5 minutes it asks to go back. Otherwise, follow the undo steps in the [install guide](INSTALL.md) |

For logs, run `adb logcat -s SmartAgenticTV`. Spoken and typed text isn't logged. Please still check logs for anything private before sharing them.
