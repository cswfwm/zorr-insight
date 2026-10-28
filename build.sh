#!/bin/sh
# 一键构建花境洞察 APK。
#
# 本容器的 aarch64 环境需要 aapt2 补丁（已在 gradle.properties 指定），
# 且 Gradle 增量编译偶尔会卡死（改完源码不重新编译），所以这里每次先清缓存。
#
# 用法：sh build.sh
# 产物：dist/花境洞察.apk
set -e
cd "$(dirname "$0")/app-source"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
GRADLE=/opt/gradle-8.9/gradle-8.9/bin/gradle
APKSIGNER=/opt/android-sdk/build-tools/34.0.0/lib/apksigner.jar
ZIPALIGN=/opt/arm64-tools/zipalign.real
SHIM=/opt/arm64-tools/libshim.so

echo "[1/3] 清理增量缓存"
rm -rf app/build/tmp/compileReleaseJavaWithJavac \
       app/build/intermediates/javac/release \
       app/build/intermediates/apk/release \
       app/build/outputs/apk/release

echo "[2/3] 编译打包"
"$GRADLE" :app:assembleRelease --no-daemon --console=plain

echo "[3/3] 对齐签名"
mkdir -p ../dist
APK=app/build/outputs/apk/release/app-release.apk
cp "$APK" ../dist/unaligned.apk
LD_PRELOAD="$SHIM" "$ZIPALIGN" -f -p 4 ../dist/unaligned.apk ../dist/aligned.apk
"$JAVA_HOME/bin/java" -jar "$APKSIGNER" sign \
    --ks app/keystore.p12 --ks-type PKCS12 \
    --ks-pass pass:neuroshoot123 --key-pass pass:neuroshoot123 \
    --ks-key-alias neuroshoot \
    --out ../dist/花境洞察.apk ../dist/aligned.apk
rm -f ../dist/unaligned.apk ../dist/aligned.apk

LD_PRELOAD="$SHIM" "$ZIPALIGN" -c 4 ../dist/花境洞察.apk
"$JAVA_HOME/bin/java" -jar "$APKSIGNER" verify ../dist/花境洞察.apk

echo "完成：$(cd .. && pwd)/dist/花境洞察.apk"
