#!/usr/bin/env bash
# Build the Liquid Glass LSPosed module with registered host adapters (libxposed API 102).
#
# No Gradle/Android Studio: this drives javac + d8 + aapt2 + apksigner directly.
# Run ./setup-tools.sh once to populate $TOOL_ROOT.
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOL_ROOT="${TOOL_ROOT:-$PROJ/tools}"
source "$PROJ/toolchain.env"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
mkdir -p "$PROJ/build"
OUT="$(mktemp -d "$PROJ/build/run-XXXXXXXX")"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$PROJ/AndroidManifest.xml")"

PLATFORM="$SDK_ROOT/platforms/$COMPILE_SDK/android.jar"
BT="$SDK_ROOT/build-tools/$BUILD_TOOLS"
D8="$TOOL_ROOT/r8-$R8_VERSION.jar"
XAPI="$TOOL_ROOT/libxposed-api-$XPOSED_API.jar"

for f in "$PLATFORM" "$BT/aapt2" "$D8" "$XAPI"; do
    [ -e "$f" ] || { echo "missing: $f  (run ./setup-tools.sh)" >&2; exit 1; }
done

javac -version 2>&1 | grep -Eq '^javac 27([.]|$)' || {
    echo "JDK 27 is required; set JAVA_HOME/PATH." >&2; exit 1;
}
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/7] javac"
find "$PROJ/src" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 --release "$JAVA_RELEASE" -Xlint:-options \
    -classpath "$PLATFORM:$XAPI" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[2/7] jar + d8"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
# Invoke d8.jar directly: the shipped wrapper hardcodes #!/bin/bash, which
# does not exist on NixOS.
java -cp "$D8" com.android.tools.r8.D8 \
    --release --lib "$PLATFORM" --min-api 26 \
    --output "$OUT/dex" \
    "$OUT/classes.jar"

echo "[3/7] aapt2 compile/link"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest "$PROJ/AndroidManifest.xml" \
    "$OUT/res.zip"

echo "[4/7] inject classes.dex + META-INF/xposed"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
STAGE="$OUT/stage"
mkdir -p "$STAGE"
cp "$OUT/dex/classes.dex" "$STAGE/classes.dex"
cp -r "$PROJ/META-INF" "$STAGE/"
# zip via python: NixOS has no zip(1), and this keeps entry order deterministic.
python3 - "$STAGE" "$OUT/unsigned.apk" <<'PYZIP'
import os, sys, zipfile
stage, apk = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a') as z:
    z.write(os.path.join(stage, 'classes.dex'), 'classes.dex',
            compress_type=zipfile.ZIP_DEFLATED)
    for root, _, files in os.walk(os.path.join(stage, 'META-INF')):
        for f in sorted(files):
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, stage),
                    compress_type=zipfile.ZIP_DEFLATED)
PYZIP

echo "[5/7] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/7] sign"
# Release signing is passed in, never stored here: this file is public.
#   KEYSTORE=path KEYSTORE_PASS=... [KEY_ALIAS=...] [KEY_PASS=...] ./build.sh
# With nothing set it falls back to a throwaway debug key, which is fine for
# sideloading but produces a different signature on every machine.
KS="${KEYSTORE:-$PROJ/debug.keystore}"
if [ -n "${KEYSTORE:-}" ]; then
    [ -f "$KS" ] || { echo "keystore not found: $KS" >&2; exit 1; }
    KS_PASS="${KEYSTORE_PASS:?KEYSTORE_PASS is required with KEYSTORE}"
    KEY_PASS="${KEY_PASS:-$KS_PASS}"
    SIGN_ARGS=(--ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS")
    [ -n "${KEY_ALIAS:-}" ] && SIGN_ARGS+=(--ks-key-alias "$KEY_ALIAS")
else
    if [ ! -f "$KS" ]; then
        keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -validity 10000 \
            -dname 'CN=Android Debug,O=Android,C=US'
    fi
    SIGN_ARGS=(--ks "$KS" --ks-pass pass:android --key-pass pass:android)
fi
FINAL="$PROJ/LiquidGlass-v$VERSION.apk"
java --enable-native-access=ALL-UNNAMED -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    "${SIGN_ARGS[@]}" --out "$FINAL" "$OUT/aligned.apk"

echo "[7/7] verify"
java --enable-native-access=ALL-UNNAMED -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify \
    --print-certs "$FINAL" | head -3
ls -la "$FINAL"
