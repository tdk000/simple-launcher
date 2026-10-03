#!/usr/bin/env bash
# =====================================================================
#  build.sh — сборка SimpleLauncher БЕЗ Gradle.
#  Только aapt2 + javac + d8 + zipalign + apksigner из Android SDK.
#
#  Нужны: JDK 11+ и Android SDK (build-tools 34, platforms;android-34).
#  Переменные окружения:
#     ANDROID_HOME   (по умолчанию /opt/android-sdk или ~/Android/Sdk)
#     JAVA_HOME
#
#  Результат: build/<имя>.apk  (подписан, выровнен, ставится на телефон)
#
#  ДВА ФЛЕЙВОРА из одного исходника:
#     bash build.sh modern   (по умолчанию) targetSdk 37 — Android 17: Pixel 6a и новее
#     bash build.sh legacy   targetSdk 23 — Android 6: Xperia Z3 Compact и старые
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")"

# ---------- флейвор ----------
FLAVOR="${1:-modern}"
case "$FLAVOR" in
  modern) TARGET_API=37; VCODE=8; VNAME="1.7"; PLATDIR="android-37.0"; OUTNAME="SimpleLauncher-android17.apk" ;;
  legacy) TARGET_API=23; VCODE=7; VNAME="1.7"; PLATDIR="android-34";     OUTNAME="SimpleLauncher-legacy.apk"  ;;
  *) echo "Использование: bash build.sh [modern|legacy]"; exit 2 ;;
esac
echo "== флейвор:  $FLAVOR (target $TARGET_API, version $VNAME/$VCODE)"

# ---------- пути к SDK ----------
if [ -z "${ANDROID_HOME:-}" ]; then
  for c in /opt/android-sdk "$HOME/Android/Sdk" "$LOCALAPPDATA/Android/Sdk" "/c/Android/Sdk"; do
    [ -d "$c" ] && ANDROID_HOME="$c" && break
  done
fi
: "${ANDROID_HOME:?Не найден Android SDK. Задай ANDROID_HOME=/путь/к/sdk}"

BT="${ANDROID_HOME}/build-tools/34.0.0"
[ -d "$BT" ] || BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
: "${BT:?В SDK нет build-tools. Поставь: sdkmanager \"build-tools;34.0.0\"}"

PLATFORM="${ANDROID_HOME}/platforms/${PLATDIR}/android.jar"
[ -f "$PLATFORM" ] || PLATFORM="$(ls "$ANDROID_HOME"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
: "${PLATFORM:?В SDK нет platforms;${PLATDIR}. Поставь: sdkmanager \"platforms;${PLATDIR}\"}"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

SRC="app/src/main"
OUT="build"
GEN="$OUT/gen"
OBJ="$OUT/classes"
APK_UNSIGNED="$OUT/app-unsigned.apk"
APK_ALIGNED="$OUT/app-aligned.apk"
APK_FINAL="$OUT/$OUTNAME"
# Keystore держим ВНЕ build/, потому что build/ очищается при каждой сборке.
# Ключ должен быть одним и тем же, иначе обновление поверх установленного
# APK упадёт с INSTALL_FAILED_UPDATE_INCOMPATIBLE.
KEYSTORE="${KEYSTORE:-$PWD/keystore/simplelauncher.jks}"

MIN_API=21

echo "== SDK:      $ANDROID_HOME"
echo "== build-tools: $BT"
echo "== platform: $PLATFORM"
echo

rm -rf "$OUT"
mkdir -p "$GEN" "$OBJ"

# ---------- 1. компиляция ресурсов ----------
echo "[1/7] aapt2 compile"
RES_FLAT="$OUT/res-flat"
mkdir -p "$RES_FLAT"
"$AAPT2" compile --dir "$SRC/res" -o "$RES_FLAT" >/dev/null
echo "      ok ($(ls "$RES_FLAT" | wc -l) файлов)"

# ---------- 2. линковка ресурсов + manifest -> R.java + base apk ----------
echo "[2/7] aapt2 link"
FLAT_LIST="$(find "$RES_FLAT" -name '*.flat' | tr '\n' ' ')"
# shellcheck disable=SC2086
"$AAPT2" link \
  -o "$APK_UNSIGNED" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version "$MIN_API" \
  --target-sdk-version "$TARGET_API" \
  --version-code "$VCODE" \
  --version-name "$VNAME" \
  --auto-add-overlay \
  $FLAT_LIST
echo "      ok -> $APK_UNSIGNED, R.java в $GEN"

# ---------- 3. javac ----------
echo "[3/7] javac"
find "$SRC/java" "$GEN" -name '*.java' > "$OUT/sources.txt"
# -bootclasspath с JDK 11+ не применяется (javac ругается), поэтому просто -classpath.
# API новее 23 в коде не используются — проверяется вручную, см. README.
javac \
  -source 11 -target 11 \
  -encoding UTF-8 \
  -nowarn \
  -classpath "$PLATFORM" \
  -d "$OBJ" \
  @"$OUT/sources.txt"
if [ -z "$(find "$OBJ" -name '*.class' 2>/dev/null)" ]; then
  echo "      !! javac не создал классы — сборка прервана"; exit 1
fi
echo "      ok ($(find "$OBJ" -name '*.class' | wc -l) классов)"

# ---------- 4. d8 -> classes.dex ----------
echo "[4/7] d8 (dex)"
CLASS_LIST="$(find "$OBJ" -name '*.class' | tr '\n' ' ')"
DEX_OUT="$OUT/dex"
mkdir -p "$DEX_OUT"
# shellcheck disable=SC2086
"$D8" --min-api "$MIN_API" --lib "$PLATFORM" --output "$DEX_OUT" $CLASS_LIST
echo "      ok -> $DEX_OUT/classes.dex ($(du -h "$DEX_OUT/classes.dex" | cut -f1))"

# ---------- 5. добавляем classes.dex в apk ----------
echo "[5/7] упаковываю classes.dex"
( cd "$DEX_OUT" && zip -q -0 "$OLDPWD/$APK_UNSIGNED" classes.dex )
echo "      ok"

# ---------- 6. zipalign ----------
echo "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$APK_UNSIGNED" "$APK_ALIGNED"
echo "      ok"

# ---------- 7. подпись ----------
echo "[7/7] apksigner"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
  echo "      создаю keystore: $KEYSTORE"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias simplelauncher \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass simplelauncher -keypass simplelauncher \
    -dname "CN=SimpleLauncher, OU=Local, O=Local, L=Local, S=Local, C=RU" >/dev/null 2>&1
  echo "      пароль: simplelauncher. СОХРАНИ этот файл — без него не обновить APK."
fi
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias simplelauncher \
  --ks-pass pass:simplelauncher \
  --key-pass pass:simplelauncher \
  --out "$APK_FINAL" \
  "$APK_ALIGNED"
"$APKSIGNER" verify --print-certs "$APK_FINAL" >/dev/null && echo "      подпись проверена"

echo
echo "======================================================================"
echo "  ГОТОВО: $APK_FINAL"
echo "  размер: $(du -h "$APK_FINAL" | cut -f1)"
echo "======================================================================"
"$BT/aapt2" dump badging "$APK_FINAL" 2>/dev/null | grep -E "^package|sdkVersion|targetSdkVersion|launchable-activity|application-label" | sed 's/^/  /'
cp "$APK_FINAL" "./$OUTNAME"
echo "  копия:      ./$OUTNAME"
echo
echo "  Установка:  adb install -r $APK_FINAL"
echo "  Или скопируй APK на телефон и открой его файловым менеджером."
