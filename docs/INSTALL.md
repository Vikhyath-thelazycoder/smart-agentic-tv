# Install guide

This takes about 20 minutes. You'll need a computer, your TV on the same Wi-Fi, and an Android phone.

**Read [SECURITY.md](../SECURITY.md) first.** Never open a board's factory menu, "Upgrade" or "Restore" screens to get this working: they can damage some boards.

## 1. Turn on ADB on the TV

1. On the TV, find its IP address in its network settings.
2. Open Developer options and turn on **USB debugging**. Many boards hide this.
3. If yours is hidden, install [Activity Launcher](https://f-droid.org/packages/de.szalkowski.activitylauncher/) from a USB stick. Use it to open `com.android.tv.settings.system.development.DevelopmentActivity`. This worked on the tested board.
4. On your computer, install [Android platform tools](https://developer.android.com/tools/releases/platform-tools) and run:

```bash
adb connect <TV IP>:5555
adb devices
```

If the TV asks to allow debugging, accept and tick **Always allow**.

Back up before changing anything:

```bash
adb shell pm list packages > packages-before.txt
adb shell settings list global > settings-global-before.txt
adb shell settings list secure > settings-secure-before.txt
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > home-before.txt
```

`home-before.txt` holds your original home screen. You'll need it to undo the change.

## 2. Install the TV app

Download `SmartAgenticTV.apk` from [Releases](../../../releases), or [build it](../README.md#build-it-yourself). Then:

```bash
adb install -r SmartAgenticTV.apk
adb shell cmd package set-home-activity io.github.smartagentictv.tv/.HomeActivity
adb shell input keyevent 3
```

The new home screen should appear. If your firmware keeps reopening its own launcher, [add a board profile](ADD-YOUR-BOARD.md).

**To undo it:** run `set-home-activity` again with the activity from `home-before.txt`, then `adb uninstall io.github.smartagentictv.tv`.

## 3. Pair your phone

1. On the TV, open **Settings > Phone**. It also shows a link where phones can get the app.
2. On your phone, on the same Wi-Fi, open that link and download the app. You can also get `MyTV.apk` from Releases. Android may ask you to allow installs from your browser.
3. Choose **Show a pairing code** on the TV, and type the code in the phone app.

The first start can take about a minute, while the TV starts its key helper.

## 4. Add your keys

In the phone app, open the **⋮** menu, then **API keys**. Paste your [free keys](API-KEYS.md) and your two-letter country code, such as `IN`. They're saved on the TV.

## 5. Close the debugging port

On the phone menu choose **Close the TV's debugging port**, or use **Settings > Phone > Debugging port** on the TV. If the TV shows `problem`, your board wouldn't let the app close it: see [SECURITY.md](../SECURITY.md).

To use `adb` again later, choose **Open it for app updates**.

## Updating

Install the new TV app with `adb install -r`. Phones then show a "new version" bar: tap it. Updates only work when signed with the same key, so if you build your own, keep your `*.keystore` files safe.

Stuck? See [troubleshooting](TROUBLESHOOTING.md).
