#!/usr/bin/env bash
# Invalid-draft UI regression; never sends a model request or persists valid settings.
set -euo pipefail
ROOT="$(realpath "$(dirname "$0")/..")"
cd "$ROOT"
B="$(mktemp -d "$ROOT/build/ui-check.XXXXXX")"
mkdir -p "$B/classes" "$B/dex"
javac --release 8 -cp tools/deps/android.jar:build/test -d "$B/classes" android/device-tests/MainActivityUiProbe.java android/device-tests/OfficialSetupProbe.java android/device-tests/EventStoreProbe.java android/device-tests/WeatherFormatProbe.java android/device-tests/WarmPromptProbe.java
jar cf "$B/probe.jar" -C "$B/classes" .
java -cp tools/deps/r8.jar com.android.tools.r8.D8 --min-api 28 --lib tools/deps/android.jar --output "$B/dex" "$B/probe.jar"
aapt2 link -o "$B/probe-unsigned.apk" --manifest android/device-tests/ui/AndroidManifest.xml -I tools/deps/resources-api34.jar
(cd "$B/dex" && zip -q -j "$B/probe-unsigned.apk" classes.dex)
zipalign -f 4 "$B/probe-unsigned.apk" "$B/probe-aligned.apk"
apksigner sign --ks signing/local.p12 --ks-pass pass:changeit --key-pass pass:changeit --ks-key-alias local --out "$B/probe.apk" "$B/probe-aligned.apk"
/root/a.sh 'umask 077; /system/bin/tee /data/local/tmp/mi-ui-probe.apk >/dev/null' < "$B/probe.apk"
/root/a.sh 'pm install -r /data/local/tmp/mi-ui-probe.apk && /system/bin/am force-stop cn.local.micompanion && /system/bin/am force-stop cn.local.micompanion.uitests && /system/bin/am instrument -w cn.local.micompanion.uitests/cn.local.micompanion.tests.MainActivityUiProbe'
