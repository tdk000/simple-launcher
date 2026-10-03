package ru.simplelauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Простой лаунчер: сетка всех запускаемых приложений, часы и строка поиска.
 * Никаких страниц, виджетов, папок, лент и тем — только список иконок.
 *
 * minSdk 21, targetSdk 23. Намеренно не используются API новее 23,
 * чтобы без проблем ставилось на Xperia Z3 Compact (Android 6.0.1).
 */
public class HomeActivity extends Activity {

    private GridView grid;
    private TextView clock;
    private TextView date;
    private TextView empty;
    private AppAdapter adapter;

    /** Список приложений перечитывается, если что-то установили/удалили. */
    private volatile boolean packagesChanged = true;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateTime(); }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { packagesChanged = true; }
    };

    // ---------------------------------------------------------------- цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_home);

        // полупрозрачный статус-бар, чтобы обои и часы выглядели цельно (API 21+)
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0x55000000);
        w.setNavigationBarColor(0x55000000);

        grid  = (GridView) findViewById(R.id.grid);
        clock = (TextView) findViewById(R.id.clock);
        date  = (TextView) findViewById(R.id.date);
        empty = (TextView) findViewById(R.id.empty);

        adapter = new AppAdapter(this);
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                launch(adapter.getItemSafe(position));
            }
        });

        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int position, long id) {
                AppItem item = adapter.getItemSafe(position);
                if (item == null) {
                    return false;
                }
                showItemMenu(item);
                return true;
            }
        });

        findViewById(R.id.search).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSearch(); }
        });

        updateTime();
        new LoadAppsTask().execute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, f);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        updateTime();
        if (packagesChanged) {
            new LoadAppsTask().execute();
        }
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) { }
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) { }
        super.onPause();
    }

    /** Кнопка «Домой» на самом лаунчере — прокручиваем список наверх. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (grid != null) {
            grid.setSelection(0);
            grid.smoothScrollToPosition(0);
        }
        updateTime();
    }

    // ---------------------------------------------------------------- часы

    private void updateTime() {
        if (clock == null) {
            return;
        }
        Calendar now = Calendar.getInstance();
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a";
        clock.setText(DateFormat.format(pattern, now));
        String d = DateFormat.format("EEEE, d MMMM", now).toString();
        date.setText(capitalize(d));
    }

    private static String capitalize(String s) {
        if (TextUtils.isEmpty(s)) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- действия

    private void launch(AppItem item) {
        if (item == null || item.component == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(item.component);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось запустить: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showItemMenu(final AppItem item) {
        final String pkg = item.component.getPackageName();
        String[] labels = new String[] {
                getString(R.string.menu_app_info),
                getString(R.string.menu_uninstall),
                getString(R.string.menu_cancel)
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(item.label)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            openAppInfo(pkg);
                        } else if (which == 1) {
                            uninstall(pkg);
                        }
                    }
                })
                .show();
    }

    private void openAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", pkg, null));
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Настройки приложения недоступны", Toast.LENGTH_SHORT).show();
        }
    }

    private void uninstall(String pkg) {
        Intent i = new Intent(Intent.ACTION_DELETE);
        i.setData(Uri.fromParts("package", pkg, null));
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось вызвать удаление", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSearch() {
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            // нет приложения для веб-поиска — пробуем просто браузер
            Intent b = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/"));
            b.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(b);
            } catch (Exception e2) {
                Toast.makeText(this, "Нет приложения для поиска", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------------------------------------------------------------- загрузка списка

    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppItem>> {

        @Override
        protected List<AppItem> doInBackground(Void... params) {
            List<AppItem> list = buildAppList();
            for (AppItem a : list) {
                try {
                    a.icon = getPackageManager().getApplicationIcon(
                            a.component.getPackageName());
                } catch (Throwable t) {
                    a.icon = null;
                }
            }
            return list;
        }

        @Override
        protected void onPostExecute(List<AppItem> result) {
            packagesChanged = false;
            adapter.setItems(result);
            if (result.isEmpty()) {
                empty.setText(R.string.no_apps);
                empty.setVisibility(View.VISIBLE);
            } else {
                empty.setVisibility(View.GONE);
            }
        }

        /** Все запускаемые приложения, кроме самого лаунчера, по алфавиту. */
        private List<AppItem> buildAppList() {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);

            String self = getPackageName();
            Collator collator = Collator.getInstance(Locale.getDefault());
            List<AppItem> list = new ArrayList<>();

            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null) {
                    continue;
                }
                ActivityInfo ai = ri.activityInfo;
                String pkg = ai.packageName;
                if (TextUtils.isEmpty(pkg) || pkg.equals(self) || "android".equals(pkg)) {
                    continue;
                }
                if (ai.name == null || ai.name.startsWith("com.android.internal.")) {
                    continue;
                }
                // не показываем неудаляемые системные служебные приложения
                ApplicationInfo app;
                try {
                    app = pm.getApplicationInfo(pkg, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean updated = (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (system && !updated && !ai.exported) {
                    continue;
                }
                CharSequence label = ri.loadLabel(pm);
                if (TextUtils.isEmpty(label)) {
                    label = ai.name;
                }
                ComponentName cn = new ComponentName(pkg, ai.name);
                boolean dup = false;
                for (AppItem ex : list) {
                    if (ex.component.equals(cn)) { dup = true; break; }
                }
                if (!dup) {
                    list.add(new AppItem(label, cn));
                }
            }

            Collections.sort(list, new Comparator<AppItem>() {
                @Override public int compare(AppItem a, AppItem b) {
                    return collator.compare(String.valueOf(a.label), String.valueOf(b.label));
                }
            });
            return list;
        }
    }

    @SuppressWarnings("unused")
    private static String sdk() {
        return Build.VERSION.RELEASE;
    }

    @SuppressWarnings("unused")
    private void setTranslucentNav() {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @SuppressWarnings("unused")
    private static int argb() { return Color.TRANSPARENT; }

    @SuppressWarnings("unused")
    private static Date now() { return new Date(); }
}
