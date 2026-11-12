#!/usr/bin/env bash
# Populate only project dependencies and the SDK packages pinned in toolchain.env.
set -euo pipefail
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PROJ/toolchain.env"
TOOL_ROOT="${TOOL_ROOT:-$PROJ/tools}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
mkdir -p "$TOOL_ROOT"
fetch() {
    local primary="$1" mirror="$2" output="$3"
    [ -s "$output" ] && return
    curl -fL --connect-timeout 15 "$primary" -o "$output" || curl -fL "$mirror" -o "$output"
}
api_path="io/github/libxposed/api/$XPOSED_API/api-$XPOSED_API.aar"
fetch "https://repo.maven.apache.org/maven2/$api_path" "https://maven.aliyun.com/repository/central/$api_path" "$TOOL_ROOT/api-$XPOSED_API.aar"
r8_path="com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
fetch "https://dl.google.com/dl/android/maven2/$r8_path" "https://maven.aliyun.com/repository/google/$r8_path" "$TOOL_ROOT/r8-$R8_VERSION.jar"
echo "$R8_SHA256  $TOOL_ROOT/r8-$R8_VERSION.jar" | sha256sum -c -
python3 - "$TOOL_ROOT" "$XPOSED_API" <<'PY'
from pathlib import Path
import sys, zipfile
root, version = Path(sys.argv[1]), sys.argv[2]
with zipfile.ZipFile(root/f'api-{version}.aar') as archive:
    (root/f'libxposed-api-{version}.jar').write_bytes(archive.read('classes.jar'))
PY
if [ ! -f "$SDK_ROOT/platforms/$COMPILE_SDK/android.jar" ] || [ ! -d "$SDK_ROOT/build-tools/$BUILD_TOOLS" ]; then
    SDKMANAGER="${SDKMANAGER:-$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager}"
    [ -x "$SDKMANAGER" ] || { echo "Install official SDK command-line tools or set SDKMANAGER." >&2; exit 1; }
    "$SDKMANAGER" --sdk_root="$SDK_ROOT" "platforms;$COMPILE_SDK" "build-tools;$BUILD_TOOLS"
fi
echo "Ready: JDK $JDK_VERSION, $COMPILE_SDK, Build Tools $BUILD_TOOLS, R8 $R8_VERSION, libxposed $XPOSED_API"
