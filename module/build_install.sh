#!/bin/bash
# ColorOS 照片有限访问全开模块 —— 无 Gradle 构建+装机脚本
# 流程：javac(-bootclasspath) → d8 → aapt2 → 重打包 → zipalign → apksigner → adb install
# 用法：bash build_install.sh [versionCode]
# @author bomo
set -euo pipefail
cd "$(dirname "$0")"
export MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1

# SDK 路径与目标设备序列号改从环境变量读取（脱敏，不硬编码本机信息）
# 用法示例：ANDROID_SDK=/your/sdk SERIAL=你的设备序列号 bash build_install.sh
AJ="${ANDROID_SDK:-$ANDROID_HOME}/platforms/android-35/android.jar"
BT="${ANDROID_SDK:-$ANDROID_HOME}/build-tools/35.0.0"
SERIAL="${SERIAL:?请通过环境变量指定目标设备序列号，例：SERIAL=xxxx bash build_install.sh}"
PKG=com.bomo.oplusmedialimited
VC="${1:-$(grep -oP 'android:versionCode="\K[0-9]+' AndroidManifest.xml)}"

rm -rf obj dexout build && mkdir -p obj dexout build

# 1. 编译（stubs 只进 -cp 不进 dex；-encoding UTF-8 防中文注释）
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" \
      -cp src/stubs -d obj $(find src/stubs src/com -name '*.java')

# 2. d8（含匿名类）
"$BT/d8.bat" --min-api 26 --lib "$AJ" --output dexout $(find obj/com -name '*.class')

# 3. aapt2 compile+link
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -I "$AJ" --manifest AndroidManifest.xml -R build/res.zip \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VC" --version-name "0.$VC" -o build/lnk.apk --auto-add-overlay

# 4. 注入 classes.dex 与 assets/xposed_init
python - <<'PYEOF'
import zipfile
zin = zipfile.ZipFile('build/lnk.apk'); zout = zipfile.ZipFile('build/pkd.apk','w',zipfile.ZIP_DEFLATED)
for i in zin.infolist(): zout.writestr(i, zin.read(i.filename))
zout.writestr('classes.dex', open('dexout/classes.dex','rb').read())
zout.writestr('assets/xposed_init', open('assets/xposed_init','rb').read())
zout.close()
PYEOF

# 5. align → sign（顺序不可反）
# keystore 与口令均不入库：keystore 走 .gitignore 排除，口令从环境变量读取
"$BT/zipalign.exe" -f 4 build/pkd.apk build/aln.apk
"$BT/apksigner.bat" sign --ks ./bomo.keystore --ks-pass "pass:${KS_PASS:?请通过环境变量指定 keystore 口令，例：KS_PASS=xxxx bash build_install.sh}" build/aln.apk
cp build/aln.apk module.apk
echo "BUILD OK -> module.apk (vc=$VC)"

# 6. 装机
adb -s "$SERIAL" install -r module.apk
echo "INSTALL OK"
