# TeleVip LSPosed

<p>
  <img src="https://img.shields.io/badge/Platform-Android-green">
  <img src="https://img.shields.io/badge/Framework-LSPosed-blue">
  <img src="https://img.shields.io/badge/License-GPL--3.0-orange">
</p>

An LSPosed / Xposed module that adds privacy and customization features to Telegram and its third-party clients.

This is a fork of [mustafa1dev/TeleVip-LSPosed](https://github.com/mustafa1dev/TeleVip-LSPosed). It keeps everything upstream does and adds support for the **R8-obfuscated official Telegram builds** (12.10.2 and later, including 13.0.0) together with a reworked **Hide Chats** feature. See [What this fork changes](#what-this-fork-changes).

## Features

### Privacy
- Hide "Seen" status in:
    - Private chats
    - Channels and groups
- Hide the "Typing..." indicator
- Hide online status
- Hide phone number
- Hide story view status
- Show deleted messages
- Prevent deletion of secret media
- **Hide Chats**: hide the chats you choose from every list in the app (see below)

### Media & Stories
- Save protected stories to the gallery
- Save voice messages
- Enable secret media
- Save message edit history

### Telegram modifications
- Remove content saving restrictions
- Disable stories
- Hide pinned messages
- Disable channel swipe
- Disable profile swipe
- Disable update notifications
- Disable number rounding

### Performance
- Boost Telegram download speed

### Premium
- Enable Local Premium

> More features are available than are listed here.

### Hide Chats

You build a list of **chats to manage**. Only chats on that list can ever be hidden, every other chat is left alone. From the managed chats you tick the ones that **stay visible**; while hiding is on, the rest are hidden, and turning it off shows all of them again.

Hidden chats disappear from every folder, the archive, contacts, search (recent searches, suggestions and results), share and forward lists, stories and unread counters. Messages forwarded out of a hidden chat are hidden in other chats too, and opening a hidden chat is refused wherever it is reached from. Hidden chats keep receiving messages. Secret chats follow their user, and the lists are saved separately for each account.

Extra options: **Hide new chats** (while hiding is on, chats and contacts that appear from then on join the list, hidden; chats that arrive while hiding is off are left alone) and **Mute hidden chats** (no notifications or badge counts). The chat picker handles any number of chats, with search, "Select all" and a "Selected only" view.

**Hide these settings** takes the whole Hide Chats section out of TeleVip's settings behind a secret code you choose (at least 4 characters, letter case does not matter). To get it back, type the code into the search box at the top of TeleVip's settings, which otherwise filters the settings by name: the field is cleared and the section shows up. That box is TeleVip's own, so nothing typed into it goes through Telegram's search or reaches its servers (Telegram's chat and contacts searches both send what you type to the server). The section locks again when the settings screen closes or Telegram goes to the background, and while it is unlocked the screen is kept out of screenshots and the recents thumbnail. The switch and the code (only a salted hash of it) are written to disk immediately, so the section stays hidden after the app is killed or the phone restarts.

## What this fork changes

- **Obfuscated Telegram builds.** Since 12.10.2 the official releases run R8 with obfuscation, so everything outside `org.telegram.messenger.*` and `org.telegram.tgnet.**` is renamed on every release. Upstream's hooks stop finding their targets. This fork loads a per-build name mapping (`app/src/main/assets/clients/<Client>/<versionCode>.json`), resolves overloads and rewritten parameter lists, and hooks the shapes that survive obfuscation. Builds without a mapping file keep the original names, so older, unobfuscated Telegram versions are unaffected.
- **Device-side name resolver.** A new release cannot have a mapping for weeks, because the sources are published well after the APK. For those builds the module matches `assets/shapes.json` (what a class extends, the types of its fields and the signatures of its methods) against the installed APK, once per Telegram build, and caches the result. Nothing is guessed: a class or member is claimed only when the evidence is unambiguous, and anything unresolved is logged and leaves the dependent feature switched off rather than half working. Measured from 12.10.6 to 13.0.0: 38/38 classes and 112/114 members resolved, none wrongly.
- **Mapping tooling.** [`tools/telegram-mapping`](tools/telegram-mapping/README.md) rebuilds an official Telegram release from [DrKLO/Telegram](https://github.com/DrKLO/Telegram) (the official builds are reproducible), reads R8's `mapping.txt`, and generates the mapping and the shapes.
- **Hide Chats reworked** around the managed list described above, replacing the earlier mode-plus-snapshot classifier.
- **Settings screen** works on builds where Telegram's own theme and layout classes cannot be found.
- Build fix: `Audio.java` renamed so the module compiles on case-sensitive file systems.
- Removed the Quran radio player that upstream starts from a long press in the settings screen.

## Supported clients

| Client | Status |
|---|---|
| Telegram from telegram.org (`org.telegram.messenger.web`) | Mapping for 12.10.6 (71129); newer builds, such as 13.0.0, through the on-device resolver |
| Telegram from Google Play / Beta | Through the on-device resolver, since its R8 run produces different names |
| Cherrygram, Nekogram, Telegraph | Bundled mappings, inherited from upstream |

The clients below are inherited from upstream's list and use unobfuscated names. This fork has not re-tested them:

Telegram 12.8.3 (69222) · Telegram Beta 12.9.0 (69579) · Telegram Web 12.8.3 (69229) · TG Connect 11.13.1 · Plus Messenger 12.8.1.0 · Nagram 12.8.1 · NagramX 12.8.1 · Nagram XF 12.7.3 · Nekogram 12.8.1 · Cherrygram 12.8.1 · Nicegram 1.55.0 · iMe 12.8.1 · iMe Direct 12.8.1 · X Plus 12.0.1 · ForkClient 12.8.4.0 · Skygram 10.20.6 · Teegra 10.3.2 · Telegraph 12.8.1.1 · Telega 2.4.3 · Momogram 12.6.4 · Forkgram Classic 12.8.10.0 · Turrit 1.8.9.9.5

Hide Chats works on the official clients, and switches itself off when a hook point it needs cannot be found in the installed build.

## Install

1. Build the APK (below) or take one from the releases page, and install it.
2. In the LSPosed manager, enable **TeleVip** and select your Telegram client in its scope. Restart the client.
3. Open Telegram's settings. The TeleVip entry is in the settings list.

On a device without root you can embed the module into the client with [LSPatch](https://github.com/LSPosed/LSPatch) instead.

## Build

Requirements: JDK 17 (the Gradle 8.2 wrapper does not run on JDK 20 or newer) and the Android SDK. Put the SDK path in `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug
./gradlew assembleRelease    # app/build/outputs/apk/release
```

The build compiles the `settingsadapter` module first and embeds its dex into the app (`DexHolder.java` is generated), so build from the repository root.

To sign a release build, create `keystore.properties` in the root. It is git-ignored, and so are `*.jks` and `*.keystore`:

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it the release build is unsigned.

## Adding a new Telegram release

See [`tools/telegram-mapping/README.md`](tools/telegram-mapping/README.md). In short: download the official APK, find the matching `update to <version>` commit in DrKLO/Telegram, and run `tools/telegram-mapping/build_mapping.sh <commit> Telegram.apk <workdir>` under JDK 17. To check how well the resolver would do on a newer release without a mapping:

```bash
python tools/telegram-mapping/shapes.py match --apk NewerTelegram.apk --shapes app/src/main/assets/shapes.json --members
```

## Testing switches

Marker files under `/data/local/tmp`, which only adb can write, enable developer options: `televip_force_resolver` (ignore the shipped mapping), `televip_open_settings` (open TeleVip's settings after startup, for builds you cannot sign in to) and `televip_test_backend` (use Telegram's test servers). They change nothing on a normal install. Details are in the [tools README](tools/telegram-mapping/README.md#testing-switches).

## Repository layout

| Path | Contents |
|---|---|
| `app/` | The module: hooks, features, settings UI, translations (`assets/lang`: en, ar, fa, zh) |
| `app/.../features/hideChats/` | Hide Chats |
| `app/.../obfuscate/` | Name mappings and the on-device `StructuralResolver` |
| `settingsadapter/` | Small module whose dex is embedded into the app for the settings list |
| `tools/telegram-mapping/` | Scripts that generate mappings and shapes for a Telegram release |

## Warning

> This module is intended for educational purposes only. Using it may cause problems with your Telegram account, including a ban or suspension. Use it at your own risk. It is unofficial and not affiliated with Telegram.

## License

Licensed under the **GNU General Public License v3.0**. See [LICENSE](./LICENSE).

## Credits

- [mustafa1dev/TeleVip-LSPosed](https://github.com/mustafa1dev/TeleVip-LSPosed), the upstream project this fork is based on. Upstream updates are announced at https://t.me/t_l0_e.
- [Re-Telegram](https://github.com/Sakion-Team/Re-Telegram), on which upstream is partially based.
- [DrKLO/Telegram](https://github.com/DrKLO/Telegram), whose reproducible builds make the mappings possible.
