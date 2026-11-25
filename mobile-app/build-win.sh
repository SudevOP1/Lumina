#!/bin/bash
# Windows (Git Bash) build. Usage: ./build-win.sh v3  -> releases/Lumina_v3.apk
set -e
V=${1:-dev}
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}
BT=$SDK/build-tools/36.1.0
AJ=$SDK/platforms/android-34/android.jar
TARSOS=../beat_detection/lib/TarsosDSP-core.jar

# Signing key lives outside the repo. Override with LUMINA_KS / LUMINA_KS_PASS (or LUMINA_KS_PASS_FILE).
KS=${LUMINA_KS:-$HOME/.keys/lumina.jks}
KS_PASS_FILE=${LUMINA_KS_PASS_FILE:-$HOME/.keys/lumina-keystore-password.txt}
if [ -n "$LUMINA_KS_PASS" ]; then KS_PASS=env:LUMINA_KS_PASS; else KS_PASS="file:$(cygpath -w "$KS_PASS_FILE")"; fi
test -f "$KS" || { echo "Keystore not found: $KS" >&2; exit 1; }

rm -rf gen obj build && mkdir -p gen obj build
"$BT/aapt.exe" package -f -m -0 arsc -J gen -M AndroidManifest.xml -S res -I "$AJ" -F build/app.unsigned.apk
javac -nowarn -Xlint:-options --release 8 -cp "$AJ;$TARSOS" -d obj $(find src gen -name '*.java') 2>&1 | grep -v -e '^warning' -e '^Note' || true
test -f obj/xyz/sudev/lumina/MainActivity.class
"$BT/d8.bat" --min-api 24 --lib "$AJ" --output build $(find obj -name '*.class') "$TARSOS"
cd build
"$BT/aapt.exe" add app.unsigned.apk classes.dex >/dev/null
"$BT/zipalign.exe" -p -f 4 app.unsigned.apk app.aligned.apk
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$KS")" --ks-pass "$KS_PASS" --out "Lumina_$V.apk" app.aligned.apk
"$BT/apksigner.bat" verify "Lumina_$V.apk"
cp "Lumina_$V.apk" ../releases/
ls -la "../releases/Lumina_$V.apk"
