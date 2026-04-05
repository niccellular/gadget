
package com.atakmap.android.gadget.plugin;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dalvik.system.DexClassLoader;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import com.atak.plugins.impl.AtakPluginRegistry;
import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class Gadget implements IPlugin {

    private static final String TAG = "Gadget";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    private final List<IPlugin> loadedPlugins = new ArrayList<>();
    private final List<PluginInfo> discoveredPlugins = new ArrayList<>();

    private static class PluginInfo {
        String packageName;
        String label;
        String implClass;
        int status; // 0=not loaded, 1=loading, 2=loaded, 3=failed
        IPlugin instance;
        View statusDot;
        TextView statusText;
        Button loadBtn;
        Button unloadBtn;
    }

    public Gadget(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        discoverPlugins();
    }

    @Override
    public void onStop() {
        for (IPlugin p : loadedPlugins) {
            try {
                p.onStop();
            } catch (Throwable e) {
                Log.e(TAG, "stop failed", e);
            }
        }
        loadedPlugins.clear();
        discoveredPlugins.clear();

        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
    }

    private void discoverPlugins() {
        discoveredPlugins.clear();
        try {
            Context atakCtx = getAtakContext();
            PackageManager pm = atakCtx.getPackageManager();

            Intent pluginIntent = new Intent("com.atakmap.app.component");
            List<ResolveInfo> candidates = pm.queryIntentActivities(pluginIntent, 0);

            AtakPluginRegistry registry = AtakPluginRegistry.get();
            Set<String> alreadyLoaded = registry.getPluginsLoaded();
            String myPkg = pluginContext.getPackageName();

            for (ResolveInfo ri : candidates) {
                String pkg = ri.activityInfo.packageName;
                if (AtakPluginRegistry.isAtak(pkg)) continue;
                if (pkg.equals(myPkg)) continue;
                if (alreadyLoaded.contains(pkg)) continue;

                PluginInfo info = new PluginInfo();
                info.packageName = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    info.label = pm.getApplicationLabel(ai).toString();
                } catch (Exception e) {
                    info.label = pkg;
                }

                try {
                    Context pkgCtx = atakCtx.createPackageContext(pkg,
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    info.implClass = readPluginXml(pkgCtx);
                } catch (Exception e) {
                    Log.w(TAG, "can't read " + pkg, e);
                }

                if (info.implClass != null) {
                    discoveredPlugins.add(info);
                    Log.i(TAG, "discovered: " + info.label + " (" + pkg + ")");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "discoverPlugins failed", e);
        }
        Log.i(TAG, "found " + discoveredPlugins.size() + " unloaded plugin(s)");
    }

    private void loadPlugin(PluginInfo info) {
        info.status = 1; // loading
        updateRow(info);

        try {
            Context atakCtx = getAtakContext();

            Context pkgCtx = atakCtx.createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);

            ApplicationInfo ai = atakCtx.getPackageManager()
                    .getApplicationInfo(info.packageName, 0);
            String dexCache = atakCtx.getDir("gadget_dex", Context.MODE_PRIVATE)
                    .getAbsolutePath();
            ClassLoader atakLoader = serviceController.getClass().getClassLoader();
            DexClassLoader pluginLoader = new DexClassLoader(
                    ai.sourceDir, dexCache, ai.nativeLibraryDir, atakLoader);

            Context wrappedCtx = new ContextWrapper(pkgCtx) {
                @Override public ClassLoader getClassLoader() { return pluginLoader; }
                @Override public Context getApplicationContext() { return this; }
                @Override public Object getSystemService(String name) {
                    Object svc = super.getSystemService(name);
                    if (LAYOUT_INFLATER_SERVICE.equals(name) && svc instanceof LayoutInflater) {
                        svc = ((LayoutInflater) svc).cloneInContext(this);
                    }
                    return svc;
                }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return atakCtx.getSharedPreferences(
                            "gadget_" + info.packageName + "_" + name, mode);
                }
                @Override public File getDir(String name, int mode) {
                    return atakCtx.getDir("gadget_" + info.packageName + "_" + name, mode);
                }
                @Override public File getFilesDir() {
                    return atakCtx.getDir("gadget_" + info.packageName, Context.MODE_PRIVATE);
                }
            };

            IServiceController wrapped = new IServiceController() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> T getService(Class<T> clz) {
                    if (clz == PluginContextProvider.class) {
                        return (T) (PluginContextProvider) () -> wrappedCtx;
                    }
                    return serviceController.getService(clz);
                }
                @Override
                public <T> boolean registerComponent(Class<T> c, T v) {
                    return serviceController.registerComponent(c, v);
                }
                @Override
                public <T> boolean unregisterComponent(Class<T> c, T v) {
                    return serviceController.unregisterComponent(c, v);
                }
            };

            Class<?> cls = pluginLoader.loadClass(info.implClass);
            Constructor<?> ctor = cls.getConstructor(IServiceController.class);
            IPlugin plugin = (IPlugin) ctor.newInstance(wrapped);
            plugin.onStart();
            loadedPlugins.add(plugin);
            info.instance = plugin;

            info.status = 2; // loaded
            Log.i(TAG, "loaded: " + info.packageName);
        } catch (Throwable e) {
            // Catch Throwable (not just Exception) because ProGuard
            // mapping mismatches cause NoClassDefFoundError which
            // extends Error, not Exception.
            info.status = 3; // failed
            Log.e(TAG, "failed to load " + info.packageName, e);
        }
        updateRow(info);
    }

    private void unloadPlugin(PluginInfo info) {
        if (info.instance != null) {
            try {
                info.instance.onStop();
                Log.i(TAG, "unloaded: " + info.packageName);
            } catch (Throwable e) {
                Log.e(TAG, "onStop failed for " + info.packageName, e);
            }
            loadedPlugins.remove(info.instance);
            info.instance = null;
        }
        info.status = 0;
        updateRow(info);
    }

    private void updateRow(PluginInfo info) {
        if (info.statusDot == null) return;

        int color;
        String statusLabel;
        switch (info.status) {
            case 1:
                color = pluginContext.getResources().getColor(R.color.status_yellow);
                statusLabel = "Loading...";
                break;
            case 2:
                color = pluginContext.getResources().getColor(R.color.status_green);
                statusLabel = "Loaded";
                break;
            case 3:
                color = pluginContext.getResources().getColor(R.color.status_red);
                statusLabel = "Failed";
                break;
            default:
                color = pluginContext.getResources().getColor(R.color.status_red);
                statusLabel = "Not loaded";
                break;
        }

        info.statusDot.getBackground().setTint(color);
        if (info.statusText != null) {
            info.statusText.setText(statusLabel);
            info.statusText.setTextColor(color);
        }
        if (info.loadBtn != null) {
            info.loadBtn.setVisibility(info.status == 2 ? View.GONE : View.VISIBLE);
            info.loadBtn.setText(info.status == 3 ? "RETRY" : "LOAD");
        }
        if (info.unloadBtn != null) {
            info.unloadBtn.setVisibility(info.status == 2 ? View.VISIBLE : View.GONE);
        }
    }

    private void showPane() {
        View root = PluginLayoutInflater.inflate(pluginContext,
                R.layout.main_layout, null);
        LinearLayout list = root.findViewById(R.id.plugin_list);
        View emptyState = root.findViewById(R.id.empty_state);
        TextView countLabel = root.findViewById(R.id.plugin_count);

        if (discoveredPlugins.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            countLabel.setText("0 plugins");
        } else {
            emptyState.setVisibility(View.GONE);
            countLabel.setText(discoveredPlugins.size() + " plugin"
                    + (discoveredPlugins.size() != 1 ? "s" : ""));

            for (PluginInfo info : discoveredPlugins) {
                View row = PluginLayoutInflater.inflate(pluginContext,
                        R.layout.plugin_row, null);

                TextView name = row.findViewById(R.id.plugin_name);
                name.setText(info.label);

                TextView pkg = row.findViewById(R.id.plugin_pkg);
                pkg.setText(info.packageName);

                info.statusDot = row.findViewById(R.id.status_dot);
                info.statusText = row.findViewById(R.id.status_text);

                info.loadBtn = row.findViewById(R.id.load_btn);
                info.loadBtn.setOnClickListener(v -> loadPlugin(info));

                info.unloadBtn = row.findViewById(R.id.unload_btn);
                info.unloadBtn.setOnClickListener(v -> unloadPlugin(info));

                updateRow(info);

                list.addView(row);
            }
        }

        templatePane = new PaneBuilder(root)
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                .build();

        uiService.showPane(templatePane, null);
    }

    private String readPluginXml(Context pkgCtx) {
        try {
            InputStream is = pkgCtx.getAssets().open("plugin.xml");
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "UTF-8");

            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG
                        && "extension".equals(parser.getName())) {
                    String type = parser.getAttributeValue(null, "type");
                    if ("gov.tak.api.plugin.IPlugin".equals(type)) {
                        String impl = parser.getAttributeValue(null, "impl");
                        is.close();
                        return impl;
                    }
                }
            }
            is.close();
        } catch (Exception e) {
            Log.e(TAG, "failed to read plugin.xml", e);
        }
        return null;
    }

    private static Context getAtakContext() throws Exception {
        return (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
    }
}
