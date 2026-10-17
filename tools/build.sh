#!/usr/bin/env bash
set -euo pipefail
ROOT="$(realpath "$(dirname "$0")/..")"
cd "$ROOT"
for tool in javac java aapt2 zip zipalign apksigner keytool; do command -v "$tool" >/dev/null; done
for dep in android.jar resources-api34.jar r8.jar xposed-api.jar; do test -f "tools/deps/$dep" || { echo "Missing tools/deps/$dep" >&2; exit 1; }; done
# Use a fresh directory without deleting any existing tree.
B="$(mktemp -d "$ROOT/build/apk.XXXXXX")"
mkdir -p "$B/classes" "$B/dex" dist signing
mapfile -t SOURCES < <(printf '%s\n' android/src/cn/local/micompanion/*.java)
javac --release 8 -encoding UTF-8 -cp tools/deps/android.jar:tools/deps/xposed-api.jar -d "$B/classes" "${SOURCES[@]}"
jar cf "$B/classes.jar" -C "$B/classes" .
java -cp tools/deps/r8.jar com.android.tools.r8.D8 --min-api 28 --lib tools/deps/android.jar --classpath tools/deps/xposed-api.jar --output "$B/dex" "$B/classes.jar"
# Debian's ARM64 aapt2 can read API34's resource table, not API36 sparse tables.
# Java still compiles against API36; resource IDs and specialUse definitions are stable since API34.
aapt2 compile --dir android/res -o "$B/res.zip"
aapt2 link -o "$B/resources.apk" --manifest android/AndroidManifest.xml -I tools/deps/resources-api34.jar -A android/assets "$B/res.zip"
cp "$B/resources.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -j "$B/unsigned.apk" classes.dex)
zipalign -f 4 "$B/unsigned.apk" "$B/aligned.apk"
if ! test -f signing/local.p12; then
 keytool -genkeypair -keystore signing/local.p12 -storetype PKCS12 -storepass changeit -keypass changeit -alias local -dname "CN=Mi Companion Local Development" -keyalg RSA -keysize 2048 -validity 3650
 chmod 600 signing/local.p12
fi
apksigner sign --ks signing/local.p12 --ks-pass pass:changeit --key-pass pass:changeit --ks-key-alias local --out dist/mi-companion.apk "$B/aligned.apk"
apksigner verify --verbose dist/mi-companion.apk
sha256sum dist/mi-companion.apk
printf 'Build artifacts: %s\n' "$B"
