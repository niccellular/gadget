# Gadget

An ATAK plugin that loads locally-built plugins on official ATAK releases, bypassing TAK's signature verification. Designed to eliminate the multi-day signing pipeline wait during plugin development.

## How It Works

Official ATAK verifies plugin signatures before loading them. Plugins not signed by the TAK pipeline are rejected. Gadget bypasses this with a JNI-based ART method hook:

1. **Gadget** is signed through the TAK pipeline and loads normally on official ATAK
2. On first plugin load, it loads `libhooker.so` which patches `AtakPluginRegistry.verifySignature` and `verifyTrust` at the ART method level — replacing their entry points with a trivial `return true`
3. Then calls `AtakPluginRegistry.loadPlugin()` — ATAK handles all classloader setup, ProGuard name resolution, shared library loaders, and resource contexts
4. The loaded plugin runs with full ATAK access, identical to a pipeline-signed plugin

## Setup

### 1. Install the ProGuard Mapping

Official ATAK obfuscates SDK class names (e.g. `IServiceController` becomes `gov.tak.api.plugin.a`). Locally-built plugins must use the same obfuscated names, or ATAK can't resolve them.

Download `mapping.zip` from the [Releases](https://github.com/niccellular/gadget/releases) page. It contains `mapping.txt` files for each ATAK version. Copy the one matching your ATAK version into your SDK root:

```bash
# Example for ATAK 5.7.0
cp mapping-5.7.0.txt /path/to/ATAK-CIV-5.7.0.0-SDK/mapping.txt
```

The SDK's build system (`-applymapping <atak.proguard.mapping>`) picks this up automatically. All subsequent `./gradlew assembleCivRelease` builds will produce correctly obfuscated bytecode.

### 2. Install Gadget

Download the signed Gadget APK for your ATAK version from [Releases](https://github.com/niccellular/gadget/releases) and install it:

```bash
adb install gadget-5.7.0-signed.apk
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

```bash
cp template.local.properties local.properties
# Edit local.properties: sdk.dir=/path/to/android/sdk

./gradlew assembleCivRelease
```

The APK will be at `app/build/outputs/apk/civ/release/ATAK-Plugin-gadget-*.apk`. This SDK-signed build works on the SDK developer build of ATAK. For official ATAK, submit the source to the TAK signing pipeline.

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
