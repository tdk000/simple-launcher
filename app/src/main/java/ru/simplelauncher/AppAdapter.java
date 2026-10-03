package ru.simplelauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер сетки. View создаётся кодом, без XML — так меньше ресурсов
 * и нет зависимости от androidx.
 */
public class AppAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<AppItem> items = new ArrayList<>();
    private int iconPx;

    public AppAdapter(Context ctx) {
        this.ctx = ctx;
        iconPx = dp(50);
    }

    public void setItems(List<AppItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public AppItem getItemSafe(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return getItemSafe(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean hasStableIds() { return false; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        View v = convertView;
        if (v == null) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(0, dp(6), 0, dp(6));

            ImageView icon = new ImageView(ctx);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconPx, iconPx);
            icon.setLayoutParams(ip);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(icon);

            TextView label = new TextView(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            label.setLayoutParams(lp);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setMaxLines(2);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            label.setShadowLayer(3f, 0f, 1f, 0xB0000000);
            box.addView(label);

            v = box;
            h = new ViewHolder(icon, label);
            v.setTag(h);
        } else {
            h = (ViewHolder) v.getTag();
        }

        AppItem item = getItemSafe(position);
        if (item != null) {
            h.label.setText(item.label);
            Drawable d = item.icon;
            if (d == null) {
                d = ctx.getResources().getDrawable(android.R.drawable.sym_def_app_icon);
            }
            h.icon.setImageDrawable(d);
            h.icon.setContentDescription(item.label);
        }
        return v;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics()));
    }

    private static final class ViewHolder {
        final ImageView icon;
        final TextView label;
        ViewHolder(ImageView i, TextView l) { icon = i; label = l; }
    }

    /** Высота строки сетки — нужна, чтобы GridView не мерил детей дважды. */
    public static int rowHeightPx(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round((50 + 6 + 6 + 4 + 30) * d);
    }

    @SuppressWarnings("unused")
    private static AbsListView.LayoutParams lp(int h) {
        return new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
    }
}
