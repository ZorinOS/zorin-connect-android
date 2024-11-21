/*
 * SPDX-FileCopyrightText: 2018 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.UserInterface;

import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.KdeConnect;
import org.kde.kdeconnect.Plugins.PluginFactory;
import com.zorinos.zorin_connect.R;

import java.util.List;

public class PluginSettingsListFragment extends PreferenceFragmentCompat {
    private static final String ARG_DEVICE_ID = "deviceId";
    private static final String KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE = "RecyclerViewLayoutmanagerState";

    private PluginPreference.PluginPreferenceCallback callback;
    private Parcelable recyclerViewLayoutManagerState;

    /*
        https://bricolsoftconsulting.com/state-preservation-in-backstack-fragments/
        When adding a fragment to the backstack the fragments onDestroyView is called (which releases
        the RecyclerView) but the fragments onSaveInstanceState is not called. When the fragment is destroyed later
        on, its onSaveInstanceState() is called but I don't have access to the RecyclerView or it's LayoutManager any more
     */
    private boolean stateSaved;

    public static PluginSettingsListFragment newInstance(@NonNull String deviceId) {
        PluginSettingsListFragment fragment = new PluginSettingsListFragment();

        Bundle args = new Bundle();
        args.putString(ARG_DEVICE_ID, deviceId);
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (requireActivity() instanceof PluginPreference.PluginPreferenceCallback) {
            callback = (PluginPreference.PluginPreferenceCallback) getActivity();
        } else {
            throw new RuntimeException(requireActivity().getClass().getSimpleName()
                    + " must implement PluginPreference.PluginPreferenceCallback");
        }

        super.onCreate(savedInstanceState);

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE)) {
            recyclerViewLayoutManagerState = savedInstanceState.getParcelable(KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        callback = null;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(preferenceScreen);

        final String deviceId = getArguments().getString(ARG_DEVICE_ID);

        Device device = KdeConnect.getInstance().getDevice(deviceId);
        if (device == null) {
            final FragmentActivity activity = requireActivity();
            activity.runOnUiThread(activity::finish);
            return;
        }

        List<String> plugins = PluginFactory.sortPluginList(device.getSupportedPlugins());
        for (final String pluginKey : plugins) {
            //TODO: Use PreferenceManagers context
            PluginPreference pref = new PluginPreference(requireContext(), pluginKey, device, callback);
            preferenceScreen.addPreference(pref);
        }
    }

    @NonNull
    @Override
    protected RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
        RecyclerView.Adapter adapter = super.onCreateAdapter(preferenceScreen);

        /*
            The layoutmanager's state (e.g. scroll position) can only be restored when the recyclerView's
            adapter has been re-populated with data.
         */
        if (recyclerViewLayoutManagerState != null) {
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onChanged() {
                    RecyclerView.LayoutManager layoutManager = getListView().getLayoutManager();

                    if (layoutManager != null) {
                        layoutManager.onRestoreInstanceState(recyclerViewLayoutManagerState);
                    }

                    recyclerViewLayoutManagerState = null;
                    adapter.unregisterAdapterDataObserver(this);
                }
            });
        }

        return adapter;
    }

    @Override
    public void onPause() {
        super.onPause();

        stateSaved = false;
    }

    @Override
    public void onResume() {
        super.onResume();

        requireActivity().setTitle(getString(R.string.device_menu_plugins));
    }

    @Override
    public void onDestroyView() {
        if (!stateSaved && getListView() != null && getListView().getLayoutManager() != null) {
            recyclerViewLayoutManagerState = getListView().getLayoutManager().onSaveInstanceState();
        }

        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        Parcelable layoutManagerState = recyclerViewLayoutManagerState;

        if (getListView() != null && getListView().getLayoutManager() != null) {
            layoutManagerState = getListView().getLayoutManager().onSaveInstanceState();
        }

        if (layoutManagerState != null) {
            outState.putParcelable(KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE, layoutManagerState);
        }

        stateSaved = true;
    }
}
