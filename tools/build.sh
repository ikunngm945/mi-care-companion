#!/usr/bin/env bash
set -euo pipefail
ROOT="$(realpath "$(dirname "$0")/..")"
cd "$ROOT"
for tool in javac java aapt2 zip zipalign apksigner keytool sha256sum; do command -v "$tool" >/dev/null; done
for dep in android.jar resources-api34.jar r8.jar xposed-api.jar shizuku-api.jar shizuku-provider.jar shizuku-aidl.jar; do test -f "tools/deps/$dep" || { echo "Missing tools/deps/$dep" >&2; exit 1; }; done
B="$(mktemp -d "$ROOT/build/apk.XXXXXX")"
mkdir -p dist signing

build() {
  local name="$1" manifest="$2" resdir="$3" assets="$4"; shift 4
  local work="$B/$name"
  mkdir -p "$work/classes" "$work/dex"
  javac --release 8 -encoding UTF-8 -cp "tools/deps/android.jar:tools/deps/xposed-api.jar:tools/deps/shizuku-api.jar:tools/deps/shizuku-aidl.jar" -d "$work/classes" "$@"
  (cd "$work/classes" && unzip -oq "$ROOT/tools/deps/shizuku-api.jar" && unzip -oq "$ROOT/tools/deps/shizuku-provider.jar" && unzip -oq "$ROOT/tools/deps/shizuku-aidl.jar")
  jar cf "$work/classes.jar" -C "$work/classes" .
  java -cp tools/deps/r8.jar com.android.tools.r8.D8 --min-api 28 --lib tools/deps/android.jar \
    --classpath tools/deps/xposed-api.jar --output "$work/dex" "$work/classes.jar"
  aapt2 compile --dir "$resdir" -o "$work/res.zip"
  aapt2 link -o "$work/resources.apk" --manifest "$manifest" \
    -I tools/deps/resources-api34.jar -A "$assets" "$work/res.zip"
  cp "$work/resources.apk" "$work/unsigned.apk"
  (cd "$work/dex" && zip -q -j "$work/unsigned.apk" classes.dex)
  zipalign -f 4 "$work/unsigned.apk" "$work/aligned.apk"
  apksigner sign --ks signing/local.p12 --ks-pass pass:changeit --key-pass pass:changeit \
    --ks-key-alias local --out "dist/$name.apk" "$work/aligned.apk"
  apksigner verify "dist/$name.apk"
  cp "dist/$name.apk" "dist/$name-0.7.0.apk"
  printf 'Built dist/%s.apk\n' "$name"
}

if ! test -f signing/local.p12; then
 keytool -genkeypair -keystore signing/local.p12 -storetype PKCS12 -storepass changeit -keypass changeit -alias local -dname "CN=Mi Companion Local Development" -keyalg RSA -keysize 2048 -validity 3650
 chmod 600 signing/local.p12
fi

APP=(android/src/cn/local/micompanion/*.java)
WEATHER=(android/src/cn/local/micompanion/*.java android/hook-common/cn/local/micompanion/hook/*.java android/module-weather/cn/local/micompanion/weather/*.java)
HEALTH=(android/src/cn/local/micompanion/*.java android/hook-common/cn/local/micompanion/hook/*.java android/module-health/cn/local/micompanion/health/*.java)

build mi-companion android/AndroidManifest.xml android/res android/assets "${APP[@]}"
build mi-companion-weather android/module-weather/AndroidManifest.xml android/module-weather/res android/module-weather/assets "${WEATHER[@]}"
build mi-companion-health android/module-health/AndroidManifest.xml android/module-health/res android/module-health/assets "${HEALTH[@]}"

(cd dist && sha256sum mi-companion-0.7.0.apk mi-companion-weather-0.7.0.apk mi-companion-health-0.7.0.apk > SHA256SUMS-0.7.0.txt)
cat dist/SHA256SUMS-0.7.0.txt
printf 'Build artifacts: %s\n' "$B"
