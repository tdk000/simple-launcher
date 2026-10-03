package ru.simplelauncher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** Одна строка сетки приложений. */
public class AppItem {
    public final CharSequence label;
    public final ComponentName component;
    public Drawable icon;

    public AppItem(CharSequence label, ComponentName component) {
        this.label = label;
        this.component = component;
    }
}
