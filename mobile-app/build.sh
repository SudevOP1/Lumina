#!/bin/bash
set -e
V=$1
# Signing key lives outside the repo. Override with LUMINA_KS / LUMINA_KS_PASS (or LUMINA_KS_PASS_FILE).
KS=${LUMINA_KS:-$HOME/.keys/lumina.jks}
KS_PASS_FILE=${LUMINA_KS_PASS_FILE:-$HOME/.keys/lumina-keystore-password.txt}
if [ -n "$LUMINA_KS_PASS" ]; then KS_PASS=env:LUMINA_KS_PASS; else KS_PASS=file:$KS_PASS_FILE; fi
test -f "$KS" || { echo "Keystore not found: $KS" >&2; exit 1; }
cd /root/lumina && rm -rf gen obj build && mkdir -p gen obj build
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
aapt package -f -m -0 arsc -J gen -M AndroidManifest.xml -S res -I $AJ -F build/app.unsigned.apk
javac -Xlint:-options -source 8 -target 8 -bootclasspath $AJ -sourcepath /root/vendor/TarsosDSP/core/src/main/java -d obj $(find -L src gen -name '*.java') 2>&1 | grep -v JAVA_TOOL || true
test -f obj/xyz/sudev/lumina/MainActivity.class
dalvik-exchange --dex --min-sdk-version=24 --output=build/classes.dex obj 2>&1 | grep -v JAVA_TOOL || true
cd build && aapt add app.unsigned.apk classes.dex >/dev/null && zipalign -p -f 4 app.unsigned.apk app.aligned.apk
apksigner sign --ks "$KS" --ks-pass "$KS_PASS" --out Lumina_$V.apk app.aligned.apk 2>&1 | grep -v JAVA_TOOL || true
apksigner verify Lumina_$V.apk 2>&1 | grep -v JAVA_TOOL || true
mkdir -p /home/claude/out && cp Lumina_$V.apk /home/claude/out/ && ls -la /home/claude/out/Lumina_$V.apk
