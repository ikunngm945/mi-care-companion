#!/usr/bin/env bash
set -euo pipefail
ROOT="$(realpath "$(dirname "$0")/..")"
cd "$ROOT"
mkdir -p build/test
javac --release 8 -cp tools/deps/android.jar:tools/deps/xposed-api.jar -d build/test android/src/cn/local/micompanion/*.java
javac --release 8 -cp build/test:tools/deps/json.jar:tools/deps/android.jar -d build/test android/tests/*Test.java
for file in android/tests/*Test.java; do
 test="$(basename "$file" .java)"
 TZ=Asia/Shanghai java -ea -cp build/test:tools/deps/json.jar:tools/deps/android.jar "cn.local.micompanion.$test"
done
if test -x build/test-venv/bin/python; then
 build/test-venv/bin/python -m unittest discover -s tests -v
else
 python3.12 -m unittest discover -s tests -v
fi
python3.12 -m py_compile astrbot_plugin_mi_companion/core.py astrbot_plugin_mi_companion/server.py astrbot_plugin_mi_companion/main.py
