package ru.simplelauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
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
    private TextView search;
    private View header;
    private AppAdapter adapter;

    /** Настройки внешнего вида (видимость строки поиска). Разрешений не требует. */
    private static final String PREFS_NAME = "simplelauncher_prefs";
    private static final String KEY_SEARCH_VISIBLE = "search_visible";
    private static final String KEY_SORT_MODE = "sort_mode";
    private SharedPreferences prefs;

    /** Критерии упорядочивания сетки. Выбор сохраняется в настройках. */
    private static final int SORT_NAME_ASC = 0;
    private static final int SORT_NAME_DESC = 1;
    private static final int SORT_INSTALL_DESC = 2;
    private static final int SORT_UPDATE_DESC = 3;
    private static final int SORT_USAGE_DESC = 4;
    private static final int SORT_SIZE_DESC = 5;
    private static final int SORT_COUNT = 6;

    private int sortMode = SORT_NAME_ASC;

    /** Последний загруженный список; пересортировывается без перечитывания пакетов. */
    private List<AppItem> lastItems = new ArrayList<>();

    /** Список приложений перечитывается, если что-то установили/удалили. */
    private volatile boolean packagesChanged = true;

    /** Экран сейчас видим: только в этом случае перечитываем список немедленно. */
    private volatile boolean resumed = false;

    /** Текущая задача загрузки, чтобы не плодить параллельные перечитывания. */
    private LoadAppsTask loadTask;

    /**
     * Установка/удаление пакетов ВО ВСЕХ профилях, включая рабочий: широковещания
     * ACTION_PACKAGE_ADDED от чужого профиля до нас не доходят, а LauncherApps.Callback
     * — доходят. Это санкционированный API для лаунчеров, разрешений не требует.
     */
    private final LauncherApps.Callback launcherCallback = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String packageName, UserHandle user) { onPackagesEvent(); }
        @Override public void onPackageRemoved(String packageName, UserHandle user) { onPackagesEvent(); }
        @Override public void onPackageChanged(String packageName, UserHandle user) { onPackagesEvent(); }
        @Override public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) { onPackagesEvent(); }
        @Override public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) { onPackagesEvent(); }
    };

    private void onPackagesEvent() {
        packagesChanged = true;
        if (resumed) {
            scheduleReload();
        }
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateTime(); }
    };

    /**
     * Приёмник живёт от onCreate до onDestroy, а не от onResume до onPause:
     * установка приложения всегда происходит ВНЕ домашнего экрана (Play Market,
     * установщик пакета), и при регистрации только в onResume широковещание
     * ACTION_PACKAGE_ADDED приходило на снятый приёмник — новый пакет не
     * появлялся в сетке, пока процесс лаунчера не перезапускался.
     */
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            packagesChanged = true;
            if (resumed) {
                scheduleReload();
            }
        }
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

        grid   = (GridView) findViewById(R.id.grid);
        clock  = (TextView) findViewById(R.id.clock);
        date   = (TextView) findViewById(R.id.date);
        empty  = (TextView) findViewById(R.id.empty);
        search = (TextView) findViewById(R.id.search);
        header = findViewById(R.id.header);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sortMode = prefs.getInt(KEY_SORT_MODE, SORT_NAME_ASC);
        if (sortMode < 0 || sortMode >= SORT_COUNT) {
            sortMode = SORT_NAME_ASC;
        }
        applySearchVisibility();

        // долгий тап по часам/дате — меню внешнего вида (скрыть/показать поиск)
        header.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showHeaderMenu(); return true; }
        });

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

        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSearch(); }
        });

        registerPackageReceiver();
        LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (la != null) {
            la.registerCallback(launcherCallback);
        }
        updateTime();
        scheduleReload();
    }

    /** Регистрация приёмника установки/удаления пакетов (живёт до onDestroy). */
    private void registerPackageReceiver() {
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerSystemReceiver(packageReceiver, pf);
    }

    /**
     * Регистрация приёмника ТОЛЬКО системных (protected) широковещаний.
     * С targetSdk 34+ registerReceiver без флага бросает SecurityException на
     * несистемные рассылки; наши все — системные, но на API 33+ явно указываем
     * RECEIVER_NOT_EXPORTED: приёмник закрыт от сторонних приложений, а системные
     * рассылки продолжают приходить. На старых API флаг недоступен — обычный вызов.
     */
    private void registerSystemReceiver(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }

    /**
     * Перечитывает список, если загрузка уже не идёт. Если задача уже работает —
     * флаг packagesChanged останется взведённым (doInBackground его сбрасывает в
     * начале), и onPostExecute запустит повторную загрузку.
     */
    private void scheduleReload() {
        if (loadTask == null || loadTask.getStatus() == AsyncTask.Status.FINISHED) {
            loadTask = new LoadAppsTask();
            loadTask.execute();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // resumed ставим ДО проверки флага: широковещание, пришедшее прямо сейчас,
        // должно сразу запустить перечитывание
        resumed = true;

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerSystemReceiver(timeReceiver, f);

        updateTime();
        if (sortMode == SORT_USAGE_DESC) {
            // счётчики запусков меняются без установки пакетов — переченьитываем
            packagesChanged = true;
        }
        if (packagesChanged) {
            scheduleReload();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) { }
        // packageReceiver НЕ снимаем: он живёт до onDestroy, иначе пропустим
        // установку, случившуюся под другим приложением
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) { }
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (la != null) {
                la.unregisterCallback(launcherCallback);
            }
        } catch (Exception ignored) { }
        super.onDestroy();
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

    // ---------------------------------------------------------------- поиск: видимость

    /** Плашка поиска показана или скрыта — по сохранённой настройке. */
    private void applySearchVisibility() {
        if (search == null) {
            return;
        }
        boolean visible = prefs.getBoolean(KEY_SEARCH_VISIBLE, true);
        search.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Долгий тап по шапке: сортировка, скрыть/показать плашку поиска, обновить список. */
    private void showHeaderMenu() {
        final boolean visible = prefs.getBoolean(KEY_SEARCH_VISIBLE, true);
        String[] labels = new String[] {
                getString(R.string.menu_sort) + ": " + sortModeLabel(sortMode),
                getString(visible ? R.string.menu_hide_search : R.string.menu_show_search),
                getString(R.string.reload),
                getString(R.string.menu_cancel)
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            showSortDialog();
                        } else if (which == 1) {
                            prefs.edit().putBoolean(KEY_SEARCH_VISIBLE, !visible).apply();
                            applySearchVisibility();
                            if (visible) {
                                Toast.makeText(HomeActivity.this, R.string.search_off_toast,
                                        Toast.LENGTH_LONG).show();
                            }
                        } else if (which == 2) {
                            packagesChanged = true;
                            scheduleReload();
                        }
                    }
                })
                .show();
    }

    /** Выбор критерия упорядочивания: список с радио-кнопками, применяется сразу. */
    private void showSortDialog() {
        final String[] labels = sortLabels();
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.menu_sort)
                .setSingleChoiceItems(labels, sortMode,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                sortMode = which;
                                prefs.edit().putInt(KEY_SORT_MODE, sortMode).apply();
                                showSorted();
                                Toast.makeText(HomeActivity.this,
                                        getString(R.string.sort_applied) + ": " + labels[which],
                                        Toast.LENGTH_SHORT).show();
                                d.dismiss();
                            }
                        })
                .show();
    }

    private String[] sortLabels() {
        return new String[] {
                getString(R.string.sort_name_asc),
                getString(R.string.sort_name_desc),
                getString(R.string.sort_install),
                getString(R.string.sort_update),
                getString(R.string.sort_usage),
                getString(R.string.sort_size)
        };
    }

    private String sortModeLabel(int mode) {
        if (mode < 0 || mode >= SORT_COUNT) {
            mode = SORT_NAME_ASC;
        }
        return sortLabels()[mode];
    }

    /** Показывает lastItems в текущем порядке — без перечитывания пакетов. */
    private void showSorted() {
        List<AppItem> sorted = new ArrayList<>(lastItems);
        Collections.sort(sorted, comparatorFor(sortMode));
        adapter.setItems(sorted);
        if (sorted.isEmpty()) {
            empty.setText(R.string.no_apps);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    /**
     * Компаратор критерия. Все критерии, кроме имени, дополняются именем как
     * вторичным ключом, чтобы порядок был устойчивым при равных значениях.
     */
    private Comparator<AppItem> comparatorFor(final int mode) {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        return new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                int r;
                switch (mode) {
                    case SORT_NAME_DESC:
                        return collator.compare(String.valueOf(b.label), String.valueOf(a.label));
                    case SORT_INSTALL_DESC:
                        r = Long.compare(b.installTime, a.installTime);
                        break;
                    case SORT_UPDATE_DESC:
                        r = Long.compare(b.updateTime, a.updateTime);
                        break;
                    case SORT_USAGE_DESC:
                        r = Integer.compare(b.launches, a.launches);
                        break;
                    case SORT_SIZE_DESC:
                        r = Long.compare(b.sizeBytes, a.sizeBytes);
                        break;
                    default:
                        r = collator.compare(String.valueOf(a.label), String.valueOf(b.label));
                        break;
                }
                if (r == 0) {
                    r = collator.compare(String.valueOf(a.label), String.valueOf(b.label));
                }
                return r;
            }
        };
    }

    private static String launchKey(int userId, String pkg) {
        return "launch:" + userId + ":" + pkg;
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
        String pkg = item.component.getPackageName();
        String key = launchKey(item.userId, pkg);
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
        // startMainActivity умеет запускать приложения ЛЮБОГО профиля, включая
        // рабочий: обычный startActivity с чужим ComponentName так не может
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (la != null && item.user != null) {
                la.startMainActivity(item.component, item.user, null, null);
                return;
            }
        } catch (Exception ignored) { }
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
        // удаление из чужого профиля штатно недоступно — там только «О приложении»
        final String[] labels = item.isWork
                ? new String[] { getString(R.string.menu_app_info), getString(R.string.menu_cancel) }
                : new String[] {
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
                            openAppInfo(item);
                        } else if (which == 1 && !item.isWork) {
                            uninstall(pkg);
                        }
                    }
                })
                .show();
    }

    private void openAppInfo(AppItem item) {
        // для рабочего профиля — штатные детали приложения того профиля
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (la != null && item.user != null) {
                la.startAppDetailsActivity(item.component, item.user, null, null);
                return;
            }
        } catch (Exception ignored) { }
        String pkg = item.component.getPackageName();
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
            // снимаем флаг В НАЧАЛЕ снимка: всё, что пришло после этой строки,
            // оставит флаг взведённым и попадёт в следующую загрузку
            packagesChanged = false;
            List<AppItem> list = buildAppList();
            for (AppItem a : list) {
                try {
                    // getBadgedIcon сам рисует бейдж рабочего профиля на значке,
                    // для личного профиля возвращает обычный значок
                    a.icon = a.info != null ? a.info.getBadgedIcon(0) : null;
                } catch (Throwable t) {
                    a.icon = null;
                }
                if (a.icon == null) {
                    try {
                        a.icon = getPackageManager().getApplicationIcon(
                                a.component.getPackageName());
                    } catch (Throwable t) {
                        a.icon = null;
                    }
                }
            }
            return list;
        }

        @Override
        protected void onPostExecute(List<AppItem> result) {
            lastItems = result;
            showSorted();
            // пока шла загрузка, могли установить ещё что-то — догружаем
            if (packagesChanged && resumed) {
                scheduleReload();
            }
        }

        /**
         * Все запускаемые приложения ВО ВСЕХ профилях (личный + рабочий), кроме
         * самого лаунчера. PackageManager видит только свой профиль, поэтому идём
         * через LauncherApps.getActivityList(null, user) — санкционированный путь
         * лаунчеров, без разрешений. Профиль в тихом режиме пропускаем: его
         * приложения сейчас недоступны для запуска.
         */
        private List<AppItem> buildAppList() {
            PackageManager pm = getPackageManager();
            LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
            List<AppItem> list = new ArrayList<>();
            if (launcherApps == null || userManager == null) {
                return list;
            }
            UserHandle me = android.os.Process.myUserHandle();
            // ВАЖНО: список профилей берём у UserManager (API 17), а не у
            // LauncherApps.getProfiles() — тот появился только в API 26 и на
            // Android 6 уронил бы лаунчер с NoSuchMethodError
            List<UserHandle> users;
            try {
                users = userManager.getUserProfiles();
            } catch (Exception e) {
                users = new ArrayList<>();
                users.add(me);
            }
            if (users == null || users.isEmpty()) {
                users = new ArrayList<>();
                users.add(me);
            }
            for (UserHandle user : users) {
                if (user == null) {
                    continue;
                }
                boolean work = !user.equals(me);
                if (work && Build.VERSION.SDK_INT >= 24) {
                    // isQuietModeEnabled появилась в API 24 — на более старых
                    // даже не вызываем (NoSuchMethodError не ловится как Exception)
                    try {
                        if (userManager.isQuietModeEnabled(user)) {
                            continue;
                        }
                    } catch (Exception ignored) { }
                }
                List<LauncherActivityInfo> acts;
                try {
                    acts = launcherApps.getActivityList(null, user);
                } catch (Exception e) {
                    continue;
                }
                if (acts == null) {
                    continue;
                }
                for (LauncherActivityInfo lai : acts) {
                    if (lai == null) {
                        continue;
                    }
                    ComponentName cn = lai.getComponentName();
                    if (cn == null) {
                        continue;
                    }
                    String pkg = cn.getPackageName();
                    if (TextUtils.isEmpty(pkg) || pkg.equals(getPackageName()) || "android".equals(pkg)) {
                        continue;
                    }
                    int uid = userIdOf(lai);
                    boolean dup = false;
                    for (AppItem ex : list) {
                        if (ex.component.equals(cn) && ex.userId == uid) { dup = true; break; }
                    }
                    if (dup) {
                        continue;
                    }
                    CharSequence label = lai.getLabel();
                    if (TextUtils.isEmpty(label)) {
                        label = cn.getClassName();
                    }
                    AppItem item = new AppItem(label, cn);
                    item.user = user;
                    item.userId = uid;
                    item.isWork = work;
                    item.info = lai;
                    item.installTime = safeInstallTime(lai);
                    // дата обновления есть только у своего профиля
                    item.updateTime = work ? 0 : safeUpdateTime(pm, pkg);
                    item.sizeBytes = safeSize(lai);
                    item.launches = prefs.getInt(launchKey(uid, pkg), 0);
                    list.add(item);
                }
            }
            return list;
        }
    }

    /** Числовой id пользователя: uid любого пакета профиля = userId * 100000 + n. */
    private static int userIdOf(LauncherActivityInfo lai) {
        try {
            ApplicationInfo ai = lai.getApplicationInfo();
            if (ai != null) {
                return ai.uid / 100000;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private static long safeInstallTime(LauncherActivityInfo lai) {
        try {
            return lai.getFirstInstallTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long safeUpdateTime(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long safeSize(LauncherActivityInfo lai) {
        try {
            ApplicationInfo ai = lai.getApplicationInfo();
            if (ai != null && ai.sourceDir != null) {
                return new java.io.File(ai.sourceDir).length();
            }
        } catch (Exception ignored) { }
        return 0;
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
