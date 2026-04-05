# Gadget

An ATAK plugin that loads other plugins which were rejected by ATAK's signature verification.

## How It Works

ATAK verifies that plugins are signed with a trusted certificate before loading them. Plugins signed with a different key are discovered but never instantiated. Gadget works around this without patching or hooking anything in ATAK.

**The approach:**

1. Gadget itself is properly signed and loads normally into ATAK
2. On start, it queries `PackageManager` for all installed packages that declare the `com.atakmap.app.component` intent filter (how ATAK discovers plugins)
3. It compares this list against `AtakPluginRegistry.getPluginsLoaded()` to find plugins that are installed but were not loaded (i.e. rejected by signature verification)
4. When the user clicks **Load**, Gadget:
   - Creates a `Context` for the target plugin via `createPackageContext()` (provides the plugin's own resources)
   - Creates a `DexClassLoader` with the plugin's APK and ATAK's classloader as parent (so both plugin classes and ATAK SDK classes are visible)
   - Wraps the context to override `getClassLoader()` and `getSystemService(LAYOUT_INFLATER_SERVICE)` so layout inflation finds both plugin resources and ATAK custom views
   - Reads the plugin's `assets/plugin.xml` to find the `IPlugin` implementation class
   - Instantiates it with a wrapped `IServiceController` that provides the correct `PluginContextProvider`
   - Calls `onStart()`

No Frida, no native code, no method hooking. Pure Java classloader bridging.

## Usage

### Prerequisites

- ATAK CIV 5.7.0 installed
- ATAK CIV 5.7.0 SDK (for building)
- Android device (arm64 or arm32)

### Building

```bash
# Copy template.local.properties to local.properties and set sdk.dir
cp template.local.properties local.properties
# Edit local.properties to set: sdk.dir=/path/to/android/sdk

# Build
./gradlew assembleCivRelease
```

The APK will be at `app/build/outputs/apk/civ/release/ATAK-Plugin-gadget-*.apk`.

### Installing

```bash
# Install Gadget (signed with SDK debug key)
adb install app/build/outputs/apk/civ/release/ATAK-Plugin-gadget-*.apk
```

The unsigned plugins you want to load must be installed on the device as Android packages. This can be done with `adb install`, by opening the APK from a file manager, or any other installation method. ATAK will discover the installed plugin but refuse to load it due to signature mismatch. Gadget picks up the difference.

### Loading Plugins

1. Open ATAK
2. Load the Gadget plugin (it will appear in the plugin manager)
3. Tap the Gadget toolbar icon (open padlock)
4. You'll see a list of installed-but-unloaded plugins
5. Tap **Load** on any plugin
6. Green dot = loaded successfully
7. Tap **Unload** to stop it

## UI

Each discovered plugin is shown as a card with:

| Element | Meaning |
|---------|---------|
| Red dot | Not loaded |
| Yellow dot | Loading |
| Green dot | Loaded and running |
| **LOAD** button | Load the plugin |
| **UNLOAD** button | Stop the plugin (calls `onStop()`) |
| **RETRY** button | Shown after a failed load attempt |

## How the Context Wrapping Works

ATAK plugins expect a `Context` from `PluginContextProvider.getPluginContext()` that provides:

- **Resources/Assets** from their own APK (for `R.layout.*`, `R.string.*`, etc.)
- **ClassLoader** that can find both their own classes and ATAK SDK classes (for custom views like `PluginSpinner`)
- **LayoutInflater** bound to the correct context (so `PluginLayoutInflater.inflate()` works)
- **SharedPreferences/Files** in a writable directory

Gadget provides all of this by wrapping the `Context` from `createPackageContext()`:

```
ContextWrapper(pkgCtx)
  getClassLoader()       -> DexClassLoader(plugin.apk, parent=ATAK classloader)
  getSystemService()     -> LayoutInflater.cloneInContext(this)
  getSharedPreferences() -> redirected to ATAK's data directory
  getFilesDir()          -> redirected to ATAK's data directory
  everything else        -> delegates to pkgCtx (plugin's own resources)
```

## Limitations

- Plugins must be installed as Android packages (via `adb install`, file manager, etc.) so that `createPackageContext` can access their resources
- **ProGuard mapping mismatch**: Official release plugins are obfuscated against the official ATAK build. If you're running the SDK/developer build of ATAK (unobfuscated), release plugins will fail with `NoClassDefFoundError` (e.g. `gov.tak.api.plugin.a` not found). This affects both Gadget and ATAK's own loader — it's not a Gadget-specific issue. Use matching builds (SDK plugin + SDK ATAK, or release plugin + release ATAK)
- Plugins that register components directly with ATAK internals (outside of `IServiceController`) may not fully clean up on unload
- The plugin's data directory is redirected to a subdirectory of ATAK's data dir, not the plugin's own package data dir
- Built for ATAK CIV 5.7.0 SDK; other versions may need the `ATAK_VERSION` in `build.gradle` adjusted

## Project Structure

```
app/src/main/
  java/.../plugin/
    Gadget.java            # Main plugin — discovery, loading, UI
    PluginNativeLoader.java # Boilerplate native loader (unused)
  res/
    layout/
      main_layout.xml      # Pane with plugin list
      plugin_row.xml       # Card for each plugin
    drawable/
      ic_launcher.xml      # Open padlock icon
      card_bg.xml          # Card background
      btn_load.xml         # Load button style
      btn_unload.xml       # Unload button style
      status_dot.xml       # Status indicator
    values/
      colors.xml           # Color palette
  assets/
    plugin.xml             # ATAK plugin descriptor
```

## License

See [license/](../../license/) in the ATAK SDK.
