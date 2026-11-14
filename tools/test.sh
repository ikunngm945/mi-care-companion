#!/usr/bin/env bash
set -euo pipefail
ROOT="$(realpath "$(dirname "$0")/..")"
cd "$ROOT"
mkdir -p build/test
SOURCE_DIRS=(
 android/src/cn/local/micompanion
 android/hook-common/cn/local/micompanion/hook
)
SOURCES=()
for dir in "${SOURCE_DIRS[@]}"; do for file in "$dir"/*.java; do SOURCES+=("$file"); done; done
SOURCES+=(android/module-health/cn/local/micompanion/health/BandFileImporter.java)
SOURCES+=(android/module-weather/cn/local/micompanion/weather/WeatherCloudClient.java)
javac --release 8 -encoding UTF-8 -cp tools/deps/android.jar:tools/deps/json.jar:tools/deps/shizuku-api.jar:tools/deps/shizuku-aidl.jar -d build/test "${SOURCES[@]}"
javac --release 8 -encoding UTF-8 -cp build/test:tools/deps/json.jar:tools/deps/android.jar -d build/test android/tests/*Test.java
for file in android/tests/*Test.java; do
 test="$(basename "$file" .java)"
 package="cn.local.micompanion"
 if [[ "$test" == "WeatherCloudClientTest" ]]; then package="$package.weather"; fi
 TZ=Asia/Shanghai java -ea -cp build/test:tools/deps/json.jar:tools/deps/android.jar "$package.$test"
done
if test -x build/test-venv/bin/python; then
 build/test-venv/bin/python -m unittest discover -s tests -v
else
 python3.12 -m unittest discover -s tests -v
fi
python3.12 -m py_compile astrbot_plugin_mi_companion/core.py astrbot_plugin_mi_companion/server.py astrbot_plugin_mi_companion/main.py
