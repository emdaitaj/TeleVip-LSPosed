# Telegram mapping generator

Since **12.10.2** the official Telegram release builds are R8-obfuscated (`TMessagesProj/proguard-rules.pro`
no longer has `-dontobfuscate` / `-dontoptimize`). Only `org.telegram.messenger.*` and `org.telegram.tgnet.**`
keep their names; everything in `org.telegram.ui` (SettingsActivity, ChatActivity, Theme, cells, ...) and most
of androidx is renamed, and R8 also inlines methods, drops unused parameters and reorders the rest.
The names change with every release.

TeleVip handles this in two steps:

1. **One mapping file per build**, `app/src/main/assets/clients/<Client>/<versionCode>.json`, loaded only for
   that exact versionCode. Older, unobfuscated Telegram versions have no file and keep the original names.
2. **`app/src/main/assets/shapes.json`**, for a build nobody has written a mapping for yet - a release that
   came out after this one, or the Google Play variant, whose R8 run gives different names again. It
   describes what obfuscation cannot change about the classes and members TeleVip uses, and
   `com.my.televip.obfuscate.runtime.StructuralResolver` matches that against the installed APK on the
   device. The result is cached per versionCode, so the work happens once per Telegram update.

   Nothing is guessed. A class is claimed only when one candidate fits clearly better than every other one
   with the same ancestry and roughly the same size; a member only when its shape is unique in the class, or
   the length of its code says which it is, or the members line up either side of whatever was added or
   removed - and where two of those have an opinion they have to agree. Anything else is left unresolved and
   logged, and a feature that would half-work with it switches itself off (`HideChats.isSupported`).

   Measured 12.10.6 -> 13.0.0 (one major release later): 38/38 classes and 112/114 members, nothing wrong.

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

The script also rewrites `app/src/main/assets/shapes.json` from the same build, and prints how much of
itself it finds again. Check a newer release against the shapes it would ship with:

```bash
python tools/telegram-mapping/shapes.py match --apk NewerTelegram.apk --shapes app/src/main/assets/shapes.json --members
```

With `--mapping` for that APK it scores the answers instead of printing them.

## Testing switches

Marker files under `/data/local/tmp`, which only adb can write, turn on the things in
`com.my.televip.utils.DevOptions`. They change nothing for a normal install.

| file | what it does |
| --- | --- |
| `televip_force_resolver` | ignores the shipped mapping, so the resolver is exercised on a build that has one |
| `televip_open_settings` | opens TeleVip's settings a few seconds after startup, the only way to see them on a build that cannot be signed in to |
| `televip_test_backend` | connects to Telegram's test servers (test numbers 99966XYYYY) |

```bash
adb shell "touch /data/local/tmp/televip_force_resolver && chmod 666 /data/local/tmp/televip_force_resolver"
adb shell pm clear org.telegram.messenger.web   # the resolver caches what it worked out
```

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
