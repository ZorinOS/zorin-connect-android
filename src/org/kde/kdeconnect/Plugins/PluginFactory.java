/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.Plugins;

import static org.apache.commons.collections4.SetUtils.emptyIfNull;
import static org.apache.commons.collections4.SetUtils.unmodifiableSet;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.atteo.classindex.ClassIndex;
import org.atteo.classindex.IndexAnnotated;
import org.kde.kdeconnect.Device;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PluginFactory {

    public static void sortPluginList(List<String> plugins) {
        plugins.sort(Comparator.comparing(o -> pluginInfo.get(o).displayName));
    }

    @IndexAnnotated
    public @interface LoadablePlugin { } //Annotate plugins with this so PluginFactory finds them

    public static class PluginInfo {

        PluginInfo(String displayName, String description, Drawable icon,
                   boolean enabledByDefault, boolean hasSettings, boolean supportsDeviceSpecificSettings,
                   boolean listenToUnpaired, String[] supportedPacketTypes, String[] outgoingPacketTypes,
                   Class<? extends Plugin> instantiableClass) {
            this.displayName = displayName;
            this.description = description;
            this.icon = icon;
            this.enabledByDefault = enabledByDefault;
            this.hasSettings = hasSettings;
            this.supportsDeviceSpecificSettings = supportsDeviceSpecificSettings;
            this.listenToUnpaired = listenToUnpaired;
            this.supportedPacketTypes = emptyIfNull(unmodifiableSet(supportedPacketTypes));
            this.outgoingPacketTypes = emptyIfNull(unmodifiableSet(outgoingPacketTypes));
            this.instantiableClass = instantiableClass;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public Drawable getIcon() {
            return icon;
        }

        public boolean hasSettings() {
            return hasSettings;
        }

        public boolean supportsDeviceSpecificSettings() { return supportsDeviceSpecificSettings; }

        public boolean isEnabledByDefault() {
            return enabledByDefault;
        }

        public boolean listenToUnpaired() {
            return listenToUnpaired;
        }

        Set<String> getOutgoingPacketTypes() {
            return outgoingPacketTypes;
        }

        public Set<String> getSupportedPacketTypes() {
            return supportedPacketTypes;
        }

        Class<? extends Plugin> getInstantiableClass() {
            return instantiableClass;
        }

        private final String displayName;
        private final String description;
        private final Drawable icon;
        private final boolean enabledByDefault;
        private final boolean hasSettings;
        private final boolean supportsDeviceSpecificSettings;
        private final boolean listenToUnpaired;
        private final Set<String> supportedPacketTypes;
        private final Set<String> outgoingPacketTypes;
        private final Class<? extends Plugin> instantiableClass;

    }

    private static final Map<String, PluginInfo> pluginInfo = new ConcurrentHashMap<>();

    public static PluginInfo getPluginInfo(String pluginKey) {
        return pluginInfo.get(pluginKey);
    }

    public static void initPluginInfo(Context context) {
        try {
            for (Class<?> pluginClass : ClassIndex.getAnnotated(LoadablePlugin.class)) {
                Plugin p = ((Plugin) pluginClass.newInstance());
                p.setContext(context, null);
                PluginInfo info = new PluginInfo(p.getDisplayName(), p.getDescription(), p.getIcon(),
                        p.isEnabledByDefault(), p.hasSettings(), p.supportsDeviceSpecificSettings(),
                        p.listensToUnpairedDevices(), p.getSupportedPacketTypes(),
                        p.getOutgoingPacketTypes(), p.getClass());
                pluginInfo.put(p.getPluginKey(), info);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Log.i("PluginFactory","Loaded "+pluginInfo.size()+" plugins");
    }

    public static Set<String> getAvailablePlugins() {
        return pluginInfo.keySet();
    }

    public static Plugin instantiatePluginForDevice(Context context, String pluginKey, Device device) {
        PluginInfo info = pluginInfo.get(pluginKey);
        try {
            Plugin plugin = info.getInstantiableClass().newInstance();
            plugin.setContext(context, device);
            return plugin;
        } catch (Exception e) {
            Log.e("PluginFactory", "Could not instantiate plugin: " + pluginKey, e);
            return null;
        }
    }

    public static Set<String> getIncomingCapabilities() {
        HashSet<String> capabilities = new HashSet<>();
        for (PluginInfo plugin : pluginInfo.values()) {
            capabilities.addAll(plugin.getSupportedPacketTypes());
        }
        return capabilities;
    }

    public static Set<String> getOutgoingCapabilities() {
        HashSet<String> capabilities = new HashSet<>();
        for (PluginInfo plugin : pluginInfo.values()) {
            capabilities.addAll(plugin.getOutgoingPacketTypes());
        }
        return capabilities;
    }

    public static Set<String> pluginsForCapabilities(Set<String> incoming, Set<String> outgoing) {
        HashSet<String> plugins = new HashSet<>();
        for (Map.Entry<String, PluginInfo> entry : pluginInfo.entrySet()) {
            String pluginId = entry.getKey();
            PluginInfo info = entry.getValue();
            //Check incoming against outgoing
            if (Collections.disjoint(outgoing, info.getSupportedPacketTypes())
                    && Collections.disjoint(incoming, info.getOutgoingPacketTypes())) {
                Log.i("PluginFactory", "Won't load " + pluginId + " because of unmatched capabilities");
                continue; //No capabilities in common, do not load this plugin
            }
            plugins.add(pluginId);
        }
        return plugins;
    }

}
