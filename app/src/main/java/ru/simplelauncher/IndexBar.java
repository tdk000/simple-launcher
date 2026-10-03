package ru.simplelauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Вертикальный индекс-бар («бегунок») справа: разделы A–Z и А–Я.
 * Тап или ведение пальца по бару выбирает раздел, сетка прыгает на его
 * первую плитку. Рисуются только разделы, которые реально есть в списке,
 * поэтому на коротком списке бар не превращается в кашу.
 *
 * Использует только API 21+: Canvas, Paint, MotionEvent.
 */
public class IndexBar extends View {

    /** Колбэк: пользователь выбрал раздел с индексом index. */
    public interface OnPickListener {
        void onSectionPicked(int index);
    }

    private final List<String> sections = new ArrayList<>();
    private OnPickListener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int activeIndex = -1;

    public IndexBar(Context ctx) {
        super(ctx);
        init();
    }

    public IndexBar(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    public IndexBar(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        init();
    }

    private void init() {
        paint.setColor(0xCCFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        activePaint.setColor(0xFF4FC3F7);
        activePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setSections(List<String> secs) {
        sections.clear();
        if (secs != null) {
            sections.addAll(secs);
        }
        activeIndex = -1;
        invalidate();
    }

    public int getSectionCount() {
        return sections.size();
    }

    public void setOnPickListener(OnPickListener l) {
        listener = l;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = sections.size();
        int top = getPaddingTop();
        int h = getHeight() - top - getPaddingBottom();
        if (n == 0 || h <= 0) {
            return;
        }
        float step = (float) h / n;
        float density = getResources().getDisplayMetrics().density;
        float textPx = Math.min(step * 0.82f, 10f * density);
        paint.setTextSize(textPx);
        activePaint.setTextSize(textPx * 1.3f);
        // если разделов больше, чем помещается читаемых подписей,
        // рисуем каждую N-ю, но трогать можно все (тач маппится на все)
        int maxLabels = Math.max(8, (int) (h / (9f * density)));
        int every = (n + maxLabels - 1) / maxLabels;
        for (int i = 0; i < n; i++) {
            if (i != activeIndex && (i % every) != 0) {
                continue;
            }
            float y = top + step * (i + 0.78f);
            canvas.drawText(sections.get(i), getWidth() / 2f, y,
                    i == activeIndex ? activePaint : paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int n = sections.size();
        int top = getPaddingTop();
        int h = getHeight() - top - getPaddingBottom();
        if (n == 0 || h <= 0) {
            return false;
        }
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                int idx = (int) ((ev.getY() - top) / (float) h * n);
                if (idx < 0) {
                    idx = 0;
                }
                if (idx > n - 1) {
                    idx = n - 1;
                }
                if (idx != activeIndex) {
                    activeIndex = idx;
                    invalidate();
                    if (listener != null) {
                        listener.onSectionPicked(idx);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeIndex = -1;
                invalidate();
                return true;
            default:
                return false;
        }
    }
}
