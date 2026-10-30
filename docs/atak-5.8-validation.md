# ATAK 5.8 validation

## Build requirements

- JDK 17 and the Gradle wrapper in this repository.
- Android SDK platform 36, NDK 25.1.8937393, and CMake 3.22.1.
- ATAK CIV 5.8 SDK, with `sdk.path` and `takdev.plugin` configured as shown in
  `template.local.properties`. Repository builds instead use `takrepo.*`.
- `mapping.txt` in the ATAK SDK root: empty only for the unobfuscated SDK host;
  the exact official host mapping for official ATAK.

Run:

```sh
./gradlew assembleCivDebug assembleCivRelease lintCivRelease
```

Inspect both APK manifests for `plugin-api=com.atakmap.app@5.8.0.CIV` and check
that the APK contains `lib/arm64-v8a/libhooker.so`. The native hook assumes the
ARM64 ART layout described in the README; the other packaged ABIs are not
validated by this checklist. Compilation and lint do not test ART patching.

The 5.8.0.3 SDK retains the registry methods used by Gadget:

- `verifySignature(Context, String)` and `verifyTrust(Context, String)` are static
  and return boolean.
- `loadPlugin(String)` and `unloadPlugin(String)` return boolean.
- Plugin discovery still uses `getPluginsLoaded()` and `isAtak(String)`.

## Official-host acceptance (requires upstream signing)

Use a test device with official ATAK CIV 5.8 and ARM64 Android 12 or later. Save
its ATAK build number, Android version, and ABI with the test results.

1. Obtain the matching official host ProGuard mapping and build a small 5.8
   test plugin using it, signed locally with the SDK development key.
2. Install the test plugin. Confirm official ATAK refuses to load it normally.
3. Install the TAK pipeline-signed 5.8 Gadget APK and enable it in ATAK's plugin
   manager. Confirm the toolbar and plugin list open without linkage errors.
4. Find the test plugin in Gadget and tap **LOAD**. Check logcat for both hook
   results, `ALL HOOKS INSTALLED`, and `loaded via ATAK`. Confirm the test
   plugin's actual UI or behavior works; a green status dot alone is insufficient.
5. Tap **UNLOAD**, verify that the test plugin stops, then load it again.
6. Restart ATAK and repeat loading to verify the process-local hooks are
   installed again. Check logcat for Java exceptions and native crashes.

Until these steps pass with a pipeline-signed build, describe the result as
build-validated 5.8 support awaiting official-host runtime validation. The
existing 0.2 release contains neither a 5.8 Gadget APK nor a 5.8 host mapping.
