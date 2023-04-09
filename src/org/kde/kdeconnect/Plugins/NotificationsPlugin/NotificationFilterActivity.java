/*
 * SPDX-FileCopyrightText: 2015 Vineet Garg <grg.vineet@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.Plugins.NotificationsPlugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.kde.kdeconnect.Helpers.ThreadHelper;
import org.kde.kdeconnect.UserInterface.ThemeUtil;
import com.zorinos.zorin_connect.R;
import com.zorinos.zorin_connect.databinding.ActivityNotificationFilterBinding;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

//TODO: Turn this into a PluginSettingsFragment
public class NotificationFilterActivity extends AppCompatActivity {
    private ActivityNotificationFilterBinding binding;
    private AppDatabase appDatabase;
    private String prefKey;

    static class AppListInfo {

        String pkg;
        String name;
        Drawable icon;
        boolean isEnabled;
    }

    private AppListInfo[] apps;

    class AppListAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return apps.length + 1;
        }

        @Override
        public AppListInfo getItem(int position) {
            return apps[position - 1];
        }

        @Override
        public long getItemId(int position) {
            return position - 1;
        }

        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                LayoutInflater inflater = getLayoutInflater();
                view = inflater.inflate(android.R.layout.simple_list_item_multiple_choice, null, true);
            }
            CheckedTextView checkedTextView = (CheckedTextView) view;
            if (position == 0) {
                checkedTextView.setText(R.string.all);
                TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(checkedTextView, null, null, null, null);
            } else {
                checkedTextView.setText(apps[position - 1].name);
                TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(checkedTextView, apps[position - 1].icon, null, null, null);
                checkedTextView.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
            }

            return view;
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.setUserPreferredTheme(this);

        binding = ActivityNotificationFilterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        appDatabase = new AppDatabase(NotificationFilterActivity.this, false);
        if (getIntent()!= null){
            prefKey = getIntent().getStringExtra(NotificationsPlugin.getPrefKey());
        }

        setSupportActionBar(binding.toolbarLayout.toolbar);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        SharedPreferences preferences = this.getSharedPreferences(prefKey, Context.MODE_PRIVATE);

        configureSwitch(preferences);

        ThreadHelper.execute(() -> {
            PackageManager packageManager = getPackageManager();
            List<ApplicationInfo> appList = packageManager.getInstalledApplications(0);
            int count = appList.size();

            apps = new AppListInfo[count];
            for (int i = 0; i < count; i++) {
                ApplicationInfo appInfo = appList.get(i);
                apps[i] = new AppListInfo();
                apps[i].pkg = appInfo.packageName;
                apps[i].name = appInfo.loadLabel(packageManager).toString();
                apps[i].icon = resizeIcon(appInfo.loadIcon(packageManager), 48);
                apps[i].isEnabled = appDatabase.isEnabled(appInfo.packageName);
            }

            Arrays.sort(apps, (lhs, rhs) -> lhs.name.compareToIgnoreCase(rhs.name));

            runOnUiThread(this::displayAppList);
        });

    }

    private void configureSwitch(SharedPreferences sharedPreferences) {
        SwitchMaterial smScreenOffNotification = findViewById(R.id.smScreenOffNotification);
        smScreenOffNotification.setChecked(
                sharedPreferences.getBoolean(getString(NotificationsPlugin.PREF_NOTIFICATION_SCREEN_OFF),false)
        );
        smScreenOffNotification.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean(getString(NotificationsPlugin.PREF_NOTIFICATION_SCREEN_OFF),isChecked).apply()
        );
    }

    private void displayAppList() {
        final ListView listView = binding.lvFilterApps;
        AppListAdapter adapter = new AppListAdapter();
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setLongClickable(true);
        listView.setOnItemClickListener((adapterView, view, i, l) -> {
            if (i == 0) {
                boolean enabled = listView.isItemChecked(0);
                for (int j = 0; j < apps.length; j++) {
                    listView.setItemChecked(j, enabled);
                }
                appDatabase.setAllEnabled(enabled);
            } else {
                boolean checked = listView.isItemChecked(i);
                appDatabase.setEnabled(apps[i - 1].pkg, checked);
                apps[i - 1].isEnabled = checked;
            }
        });
        listView.setOnItemLongClickListener((adapterView, view, i, l) -> {
            if(i == 0)
                return true;
            Context context = this;
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View mView = getLayoutInflater().inflate(R.layout.popup_notificationsfilter, null);
            builder.setMessage(context.getResources().getString(R.string.extra_options));

            ListView lv = mView.findViewById(R.id.extra_options_list);
            final String[] options = new String[] {
                    context.getResources().getString(R.string.privacy_options)
            };
            ArrayAdapter<String> extra_options_adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, options);
            lv.setAdapter(extra_options_adapter);
            builder.setView(mView);

            AlertDialog ad = builder.create();

            lv.setOnItemClickListener((new_adapterView, new_view, new_i, new_l) -> {
                switch (new_i){
                    case 0:
                        AlertDialog.Builder myBuilder = new AlertDialog.Builder(context);
                        String packageName = apps[i - 1].pkg;

                        View myView = getLayoutInflater().inflate(R.layout.privacy_options, null);
                        CheckBox checkbox_contents = myView.findViewById(R.id.checkbox_contents);
                        checkbox_contents.setChecked(appDatabase.getPrivacy(packageName, AppDatabase.PrivacyOptions.BLOCK_CONTENTS));
                        checkbox_contents.setText(context.getResources().getString(R.string.block_contents));
                        CheckBox checkbox_images = myView.findViewById(R.id.checkbox_images);
                        checkbox_images.setChecked(appDatabase.getPrivacy(packageName, AppDatabase.PrivacyOptions.BLOCK_IMAGES));
                        checkbox_images.setText(context.getResources().getString(R.string.block_images));

                        myBuilder.setView(myView);
                        myBuilder.setTitle(context.getResources().getString(R.string.privacy_options));
                        myBuilder.setPositiveButton(context.getResources().getString(R.string.ok), (dialog, id) -> dialog.dismiss());
                        myBuilder.setMessage(context.getResources().getString(R.string.set_privacy_options));

                        checkbox_contents.setOnCheckedChangeListener((compoundButton, b) ->
                                appDatabase.setPrivacy(packageName, AppDatabase.PrivacyOptions.BLOCK_CONTENTS,
                                        compoundButton.isChecked()));
                        checkbox_images.setOnCheckedChangeListener((compoundButton, b) ->
                                appDatabase.setPrivacy(packageName, AppDatabase.PrivacyOptions.BLOCK_IMAGES,
                                        compoundButton.isChecked()));

                        ad.cancel();
                        myBuilder.show();
                        break;
                }
            });

            ad.show();
            return true;
        });

        listView.setItemChecked(0, appDatabase.getAllEnabled()); //"Select all" button
        for (int i = 0; i < apps.length; i++) {
            listView.setItemChecked(i + 1, apps[i].isEnabled);
        }

        listView.setVisibility(View.VISIBLE);
        binding.spinner.setVisibility(View.GONE);
    }

    private Drawable resizeIcon(Drawable icon, int maxSize) {
        Resources res = getResources();

        //Convert to display pixels
        maxSize = (int) (maxSize * res.getDisplayMetrics().density);

        Bitmap bitmap = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        icon.draw(canvas);

        return new BitmapDrawable(res, bitmap);

    }

    @Override
    public boolean onSupportNavigateUp() {
        super.onBackPressed();
        return true;
    }
}
