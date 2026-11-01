#!/usr/bin/env bash
# Builds MyTV.apk without Gradle, like my-tv-home/build.sh. Wire.java is shared with the TV app.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" # d8 and apksigner look for java themselves
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
WIRE=../tv/app/src/io/github/smartagentictv/wire/Wire.java

rm -rf out && mkdir -p out/classes out/gen
"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version 26 --target-sdk-version 34
"$JDK/bin/javac" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d out/classes $(find app/src -name '*.java') "$WIRE"
"$BT/d8" --release --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# Updates need the same key, so keep this file private and safe.
if [ ! -f phone.keystore ]; then
  "$JDK/bin/keytool" -genkeypair -keystore phone.keystore -alias phone -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass mytvphone -keypass mytvphone -dname "CN=My TV Phone" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks phone.keystore --ks-pass pass:mytvphone --key-pass pass:mytvphone --out MyTV.apk out/aligned.apk
echo "Built MyTV.apk ($(du -h MyTV.apk | cut -f1))"
