#!/usr/bin/env bash
# Run before every pull request. CI runs this too.
set -euo pipefail
cd "$(dirname "$0")/.."
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "== Text rules"
"$JDK/bin/javac" -nowarn -encoding UTF-8 -d "$OUT/words" apps/tv/app/src/io/github/smartagentictv/tv/Words.java tools/JarvisTest.java
"$JDK/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT/words" io.github.smartagentictv.tv.JarvisTest

echo "== Voice rules"
"$JDK/bin/javac" -nowarn -encoding UTF-8 -d "$OUT/voice" apps/phone/app/src/io/github/smartagentictv/phone/VoiceCommands.java tools/VoiceTest.java
"$JDK/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT/voice" io.github.smartagentictv.phone.VoiceTest | tail -1

echo "== Link test compiles"
"$JDK/bin/javac" -nowarn -encoding UTF-8 -d "$OUT/link" tools/LinkTest.java apps/tv/app/src/io/github/smartagentictv/wire/Wire.java

echo "== Translations"
python3 tools/translate.py --check

echo "== No keys in tracked files"
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if git grep -nIE 'AIza[0-9A-Za-z_-]{30,}|gsk_[0-9A-Za-z]{20,}|sk-[A-Za-z0-9]{20,}|ghp_[0-9A-Za-z]{30,}|eyJ[A-Za-z0-9_-]{30,}' -- . ':!tools/check.sh'; then
    echo "A key seems to be in the code. Remove it." >&2
    exit 1
  fi
  if git ls-files | grep -E '(^|/)config\.properties$|\.keystore$|\.jks$|pairing|\.apk$'; then
    echo "A private file is tracked. Remove it." >&2
    exit 1
  fi
fi

echo "== Build"
./build.sh
echo "All checks passed"
