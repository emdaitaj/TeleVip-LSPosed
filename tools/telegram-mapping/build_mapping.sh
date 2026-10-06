#!/usr/bin/env bash
# Builds the official Telegram sources of one release with R8 and writes the TeleVip mapping for it.
#
#   JAVA_HOME=<jdk 17> tools/telegram-mapping/build_mapping.sh <telegram commit> <official apk> [work dir]
#
# The commit is the "update to x.y.z" commit of github.com/DrKLO/Telegram for the APK's version.
# On Windows keep the work dir short (e.g. C:/tgb), aidl.exe fails on long paths.
set -euo pipefail
export PYTHONUTF8=1

COMMIT=$1
APK=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
WORK=${3:-$HOME/tg-mapping-build}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17, Telegram is built with gradle:8.13-jdk17 and other javac versions give different dex}"

AAPT2=$(ls "$SDK"/build-tools/*/aapt2* | tail -1)
BADGING=$("$AAPT2" dump badging "$APK" 2>/dev/null | head -1)
PACKAGE=$(echo "$BADGING" | sed -E "s/^package: name='([^']+)'.*/\1/")
VERSION_CODE=$(echo "$BADGING" | sed -E "s/.*versionCode='([0-9]+)'.*/\1/")
VERSION_NAME=$(echo "$BADGING" | sed -E "s/.*versionName='([^']+)'.*/\1/")

case "$PACKAGE" in
    org.telegram.messenger.web) CLIENT=TelegramWeb; TASK=:TMessagesProj_AppStandalone:minifyAfatStandaloneWithR8; MAPPING=TMessagesProj_AppStandalone/build/outputs/mapping/afatStandalone/mapping.txt ;;
    # not verified yet, the generator rejects the mapping if this is not the variant Google Play ships
    org.telegram.messenger) CLIENT=Telegram; TASK=:TMessagesProj_App:minifyBundleAfatReleaseWithR8; MAPPING=TMessagesProj_App/build/outputs/mapping/bundleAfatRelease/mapping.txt ;;
    *) echo "unsupported package $PACKAGE" >&2; exit 1 ;;
esac
echo "$CLIENT $VERSION_NAME ($VERSION_CODE), Telegram commit $COMMIT"

fetch() { # dir url commit
    mkdir -p "$1"
    (cd "$1"
     [ -d .git ] || { git init -q; git remote add origin "$2"; }
     git config core.longpaths true
     git config core.autocrlf false
     git fetch -q --depth 1 origin "$3"
     git checkout -q -f FETCH_HEAD)
}

fetch "$WORK" https://github.com/DrKLO/Telegram.git "$COMMIT"
cd "$WORK"
# only the Java submodules matter for the dex
fetch TMessagesProj_Modules/media https://github.com/Arseny271/media.git "$(git ls-tree HEAD TMessagesProj_Modules/media | awk '{print $3}')"
fetch TMessagesProj/lib/jlatexmath https://github.com/dkaraush/jlatexmath-android.git "$(git ls-tree HEAD TMessagesProj/lib/jlatexmath | awk '{print $3}')"

# native code does not change the dex, skip building it
python - <<'EOF'
import re
p = "TMessagesProj/build.gradle"
s = open(p, encoding="utf-8").read()
while True:
    m = re.search(r"externalNativeBuild\s*\{", s)
    if not m:
        break
    depth, k = 0, s.index("{", m.start())
    while True:
        depth += {"{": 1, "}": -1}.get(s[k], 0)
        if depth == 0:
            break
        k += 1
    s = s[:m.start()] + s[k + 1:]
open(p, "w", encoding="utf-8", newline="").write(s)
EOF
echo "sdk.dir=$(echo "$SDK" | sed 's/\\/\//g; s/:/\\:/')" > local.properties

./gradlew --no-daemon "$TASK" -x lint

python "$HERE/generate_mapping.py" \
    --mapping "$MAPPING" \
    --apk "$APK" \
    --src "$ROOT/app/src/main/java" \
    --alias "$ROOT/app/src/main/assets/clients/Alias/Nekogram.json" "$ROOT/app/src/main/assets/clients/Alias/Cherrygram.json" \
    --out "$ROOT/app/src/main/assets/clients/$CLIENT/$VERSION_CODE.json" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
