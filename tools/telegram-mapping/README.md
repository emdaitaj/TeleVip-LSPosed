# Telegram mapping generator

Since **12.10.2** the official Telegram release builds are R8-obfuscated (`TMessagesProj/proguard-rules.pro`
no longer has `-dontobfuscate` / `-dontoptimize`). Only `org.telegram.messenger.*` and `org.telegram.tgnet.**`
keep their names; everything in `org.telegram.ui` (SettingsActivity, ChatActivity, Theme, cells, ...) and most
of androidx is renamed, and R8 also inlines methods, drops unused parameters and reorders the rest.
The names change with every release.

TeleVip handles this with one mapping file per build, `app/src/main/assets/clients/<Client>/<versionCode>.json`.
It is only loaded for that exact versionCode; older, unobfuscated Telegram versions have no file and keep
using the original names.

## Adding a new Telegram release

The official builds are reproducible: building the release commit of
[DrKLO/Telegram](https://github.com/DrKLO/Telegram) gives byte-identical dex files, so R8's own `mapping.txt`
describes the official APK exactly.

1. Download the official APK (telegram.org for `TelegramWeb`).
2. Find the commit `update to <version> (<build>)` on DrKLO/Telegram.
3. Run, with a JDK **17** (Telegram is built with `gradle:8.13-jdk17`, other javac versions give a different dex):

   ```bash
   JAVA_HOME=/path/to/jdk-17 tools/telegram-mapping/build_mapping.sh <commit> Telegram.apk C:/tgb
   ```

   On Windows keep the work dir short, `aidl.exe` fails on long paths. The first build downloads the
   dependencies and takes a while, the native code is skipped.
4. Read the report it prints, then rebuild TeleVip.

`generate_mapping.py` verifies every name against the APK and refuses a mapping that does not belong to it.
The names it maps are collected from TeleVip's sources: `"ClassKey", "member"` string pairs
(`Obfuscate.getMethodName("ChatActivity", "createView")`, `TgAccess.get(obj, "UItem", "id")`, ...) and
`"org.telegram..."` class names. A name built at runtime is not seen, write it as a literal pair.

## The report

- **overloads renamed differently**: the overloads of that method got different names; call it with
  `Obfuscate.getMethodName(classKey, name, "int,TLObject")` (original parameter types).
- **parameters changed by R8**: the generated `params` hold the real parameter list, so hook through
  `ArgsResolver.merge(classKey, name, ...)` and look arguments up by type. For calls, `argOrder` holds the new
  order, see `Obfuscate.arrangeArgs`.
- **inlined into a synthetic wrapper**: the method body now lives in a lambda/accessor; the mapping points
  the method name at it (with `params`), hooks keep working.
- **inlined by R8, not hookable**: the method no longer exists, the feature needs another hook point.
