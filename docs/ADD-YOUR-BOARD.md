# Add your board

A board profile is a small text file. You don't need to write any Java.

**Only read from the board while doing this.** Never start factory test apps or firmware tools to find out what they do.

## 1. Collect the facts

```bash
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.hardware
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cat /proc/bus/input/devices | grep -E '^N:'
adb shell pm list packages | grep -iE 'factory|upgrade|test|launcher|provision'
```

## 2. Write the profile

Create `apps/tv/app/assets/boards/<your-board>.properties`:

```properties
# Board name, chip, firmware, Android version. What you tested it on.
# An app only this board has: it picks this profile.
match=com.vendor.launcher
# The firmware's launcher, if it keeps reopening: it gets sent back to the new home screen.
old_home=com.vendor.launcher
# Names from /proc/bus/input/devices. Leave empty if there's no such device.
mouse_device=
keyboard_device=
# The scan code of the remote's Source button, if Android has no key for it. 0 means none.
source_scan_code=0
# package/activity, optional shortcuts.
settings_activity=
miracast_activity=
# Comma separated: factory, firmware and other apps the phone must never open.
never_open=com.vendor.factorytest,com.vendor.upgrade
```

Pick a `match` app that only your board has. The first matching file in name order is used, and anything you leave out comes from `default.properties`.

To find a scan code, run `adb shell getevent -l`, press the button on the real remote, and read the value. Read-only use is fine.

## 3. Test it

Build and install, then run `adb logcat -s SmartAgenticTV`. You should see `board profile: <your file>`. Try the remote, mouse and Source from the phone.

## 4. Send it in

Open a pull request with your profile and a new row in [SUPPORTED-BOARDS.md](SUPPORTED-BOARDS.md), saying what you tested.
