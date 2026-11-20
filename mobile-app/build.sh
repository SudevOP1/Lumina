#!/bin/bash
set -e
V=$1
cd /root/lumina && rm -rf gen obj build && mkdir -p gen obj build
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
aapt package -f -m -0 arsc -J gen -M AndroidManifest.xml -S res -I $AJ -F build/app.unsigned.apk
javac -Xlint:-options -source 8 -target 8 -bootclasspath $AJ -sourcepath /root/vendor/TarsosDSP/core/src/main/java -d obj $(find -L src gen -name '*.java') 2>&1 | grep -v JAVA_TOOL || true
test -f obj/xyz/sudev/lumina/MainActivity.class
dalvik-exchange --dex --min-sdk-version=24 --output=build/classes.dex obj 2>&1 | grep -v JAVA_TOOL || true
cd build && aapt add app.unsigned.apk classes.dex >/dev/null && zipalign -p -f 4 app.unsigned.apk app.aligned.apk
apksigner sign --ks ../key.jks --ks-pass pass:android --key-pass pass:android --out Lumina_$V.apk app.aligned.apk 2>&1 | grep -v JAVA_TOOL || true
apksigner verify Lumina_$V.apk 2>&1 | grep -v JAVA_TOOL || true
mkdir -p /home/claude/out && cp Lumina_$V.apk /home/claude/out/ && ls -la /home/claude/out/Lumina_$V.apk
