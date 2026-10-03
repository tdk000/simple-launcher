package ru.simplelauncher;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

/** Одна строка сетки приложений. */
public class AppItem {
    public final CharSequence label;
    public final ComponentName component;
    public Drawable icon;

    /** Метаданные для сортировок: установка, обновление, размер APK, запуски. */
    public long installTime;
    public long updateTime;
    public long sizeBytes;
    public int launches;

    /** Профиль (личный или рабочий), в котором установлено приложение. */
    public UserHandle user;
    public int userId;
    public boolean isWork;
    /** Источник значка/метаданных для любого профиля (LauncherApps). */
    public LauncherActivityInfo info;

    public AppItem(CharSequence label, ComponentName component) {
        this.label = label;
        this.component = component;
    }
}
