# Gadget

An ATAK plugin that loads locally-built plugins on official ATAK releases, bypassing TAK's signature verification. Designed to eliminate the multi-day signing pipeline wait during plugin development.

## How It Works

Official ATAK verifies plugin signatures before loading them. Plugins not signed by the TAK pipeline are rejected. Gadget bypasses this with a JNI-based ART method hook:

1. **Gadget** is signed through the TAK pipeline and loads normally on official ATAK
2. On first plugin load, it loads `libhooker.so` which patches `AtakPluginRegistry.verifySignature` and `verifyTrust` at the ART method level — replacing their entry points with a trivial `return true`
3. Then calls `AtakPluginRegistry.loadPlugin()` — ATAK handles all classloader setup, ProGuard name resolution, shared library loaders, and resource contexts
4. The loaded plugin runs with full ATAK access, identical to a pipeline-signed plugin

## ATAK 5.8 support

The source targets the ATAK `5.8.0` plugin API (`com.atakmap.app@5.8.0.CIV`),
including ATAK CIV 5.8.0.3. Build with the matching 5.8 SDK. Gadget itself still
requires TAK pipeline signing before official ATAK will load it.

The current `0.2` release assets only cover ATAK 5.5–5.7. They do not include a
5.8 Gadget APK or a 5.8 ProGuard mapping. Do not reuse those APKs or mappings on
5.8. A signed 5.8 Gadget build and the matching official host mapping must be
provided before the official-host workflow below can be validated. See
[the 5.8 validation checklist](docs/atak-5.8-validation.md).

## Setup

### 1. Install the ProGuard Mapping

Official ATAK obfuscates SDK class names (e.g. `IServiceController` becomes `gov.tak.api.plugin.a`). Locally-built plugins must use the same obfuscated names, or ATAK can't resolve them.

Obtain the ProGuard mapping for the exact official ATAK build you use. For older supported versions, `mapping.zip` on the [Releases](https://github.com/niccellular/gadget/releases) page contains versioned mappings. That archive currently has no 5.8 mapping. Once a matching 5.8 mapping is available, copy it into your SDK root:

```bash
# Use the mapping supplied for your exact ATAK 5.8 host build.
cp /path/to/matching-host-mapping.txt /path/to/ATAK-CIV-5.8.0.3-SDK/mapping.txt
```

The SDK's build system (`-applymapping <atak.proguard.mapping>`) picks this up automatically. All subsequent `./gradlew assembleCivRelease` builds will produce correctly obfuscated bytecode.

### 2. Install Gadget

Obtain a TAK pipeline-signed Gadget APK for ATAK 5.8 and install it. Until a 5.8 release is published, this requires submitting the updated source to the signing pipeline:

```bash
adb install /path/to/gadget-5.8.0-signed.apk
```

### 3. Install Your Plugin

Build your plugin locally and install it on the device:

```bash
cd /path/to/your-plugin
./gradlew assembleCivRelease
adb install app/build/outputs/apk/civ/release/*.apk
```

ATAK will discover the plugin but refuse to load it (signature mismatch). Gadget picks up the difference.

## Loading Plugins

1. Open ATAK
2. Load the Gadget plugin from the plugin manager
3. Tap the Gadget toolbar icon (open padlock)
4. You'll see a list of installed-but-unloaded plugins
5. Tap **Load** on any plugin
6. Green dot = loaded and running
7. Tap **Unload** to stop it

## UI

| Element | Meaning |
|---------|---------|
| Red dot | Not loaded |
| Yellow dot | Loading |
| Green dot | Loaded and running |
| **LOAD** | Load the plugin |
| **UNLOAD** | Stop the plugin |
| **RETRY** | Shown after a failed attempt |

## Dev Workflow

Once set up, the development cycle is:

1. Edit plugin code
2. `./gradlew assembleCivRelease`
3. `adb install app/build/outputs/apk/civ/release/*.apk`
4. Tap **Load** in Gadget

No pipeline submission, no signing wait.

## How the Hook Works

The JNI hooker (`hooker.c`) performs ART method replacement:

1. Gets `jmethodID` for `verifySignature` and `verifyTrust` — on ART, `jmethodID` IS the `ArtMethod*` pointer
2. Detects `ArtMethod` struct size by comparing pointers of two consecutive methods (`alwaysTrue1`, `alwaysTrue2`)
3. Copies `data_` and `entry_point_from_quick_compiled_code_` (offset 16+) from `alwaysTrue1` to each target method
4. Preserves `declaring_class_` and `access_flags_` (offset 0-15) so ART's method resolution still works

`alwaysTrue1` is a trivial Java method (`return true`) whose bytecode (`const/4 v0, 1; return v0`) needs zero DEX constant pool resolution — safe to run in any DEX context.

After hooking, `AtakPluginRegistry.loadPlugin()` is called. ATAK handles everything: classloader creation with shared library loaders, ProGuard-obfuscated class resolution, resource contexts, native library loading, and plugin lifecycle management.

## Building from Source

Use JDK 17, Android SDK platform 36, NDK `25.1.8937393`, CMake `3.22.1`,
and the ATAK CIV 5.8 SDK. Android Gradle Plugin can install missing native tools
if the Android SDK licenses have already been accepted.

For a local SDK developer-host build, if the SDK has no `mapping.txt`, create
an empty one in that SDK directory. This preserves SDK class names and is only
for the unobfuscated SDK host. For official ATAK, use the exact host's real
mapping instead; an empty file or another version's mapping is insufficient.

```bash
cp template.local.properties local.properties
# Edit all three paths: sdk.dir, sdk.path, and takdev.plugin.
# sdk.path is the ATAK SDK root containing main.jar and android_keystore.
# takdev.plugin is that SDK's atak-gradle-takdev.jar.

./gradlew assembleCivDebug assembleCivRelease lintCivRelease
```

APKs are written to `app/build/outputs/apk/civ/{debug,release}/`. These local
builds use the SDK development key. Official ATAK requires a TAK pipeline-signed
Gadget build and matching obfuscation. Submit the source to the TAK signing
pipeline; a successful local build alone does not establish official-host loading.

## Project Structure

```
app/src/main/
  java/.../plugin/
    Gadget.java            # Plugin discovery, UI, hook loading, loadPlugin
    PluginNativeLoader.java # Native library loader utility
  cpp/
    hooker.c               # ART method replacement (verifySignature/verifyTrust)
    CMakeLists.txt          # Native build config
  res/
    layout/
      main_layout.xml      # Plugin list pane
      plugin_row.xml       # Card per plugin
    drawable/
      ic_launcher.xml      # Open padlock icon
  assets/
    plugin.xml             # ATAK plugin descriptor
```

## Limitations

- Plugins must be installed as Android packages (`adb install`, file manager, etc.)
- The ProGuard mapping must match the ATAK version on the device — a 5.7.0 mapping won't work with ATAK 5.6.0
- Gadget must be signed through the TAK pipeline for each ATAK version you target
- The ART method hook assumes a 32-byte `ArtMethod` struct with `data_` at offset 16 and `entry_point` at offset 24 (ARM64, Android 12+). Other architectures or future ART changes may require adjustment

## License

See [license/](../../license/) in the ATAK SDK.
