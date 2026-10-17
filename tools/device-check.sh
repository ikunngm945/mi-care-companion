#!/usr/bin/env bash
# Read-only instrumentation: no settings, events or network calls. Private payload stays under build/.
set -euo pipefail
ROOT="$(realpath "$(dirname "$0")/..")"
cd "$ROOT"
B="$(mktemp -d "$ROOT/build/device-check.XXXXXX")"
mkdir -p "$B/classes" "$B/dex"
javac --release 8 -cp tools/deps/android.jar:build/test -d "$B/classes" android/device-tests/ReadOnlyProbe.java
jar cf "$B/probe.jar" -C "$B/classes" .
jar cf "$B/target.jar" -C build/test cn/local/micompanion
java -cp tools/deps/r8.jar com.android.tools.r8.D8 --min-api 28 --lib tools/deps/android.jar --classpath "$B/target.jar" --output "$B/dex" "$B/probe.jar"
aapt2 link -o "$B/probe-unsigned.apk" --manifest android/device-tests/AndroidManifest.xml -I tools/deps/resources-api34.jar
(cd "$B/dex" && zip -q -j "$B/probe-unsigned.apk" classes.dex)
zipalign -f 4 "$B/probe-unsigned.apk" "$B/probe-aligned.apk"
apksigner sign --ks signing/local.p12 --ks-pass pass:changeit --key-pass pass:changeit --ks-key-alias local --out "$B/probe.apk" "$B/probe-aligned.apk"
/root/a.sh 'umask 077; /system/bin/tee /data/local/tmp/mi-companion-probe.apk >/dev/null' < "$B/probe.apk"
/root/a.sh 'pm install -r /data/local/tmp/mi-companion-probe.apk && /system/bin/am instrument -w cn.local.micompanion.tests/.ReadOnlyProbe'
umask 077
/root/a.sh '/system/bin/tee </data/user/0/cn.local.micompanion/cache/readonly-probe-private.json' > build/device-payload-private.json
