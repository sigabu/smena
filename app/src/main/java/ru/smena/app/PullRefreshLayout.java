package ru.smena.app;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A small native pull-to-refresh container. Only drags started at the top qualify. */
@android.annotation.SuppressLint("ViewConstructor") // Built programmatically with an existing ScrollView; never inflated from XML.
public final class PullRefreshLayout extends FrameLayout {
    private final ScrollView scroll;
    private final TextView indicator;
    private final int slop;
    private final float threshold;
    private float startX, startY, pull;
    private boolean eligible, dragging, refreshing;
    private Runnable onRefresh;

    public PullRefreshLayout(Context context, ScrollView scroll, int textColor, int backgroundColor) {
        super(context);
        this.scroll = scroll;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        threshold = dp(72);
        addView(scroll, new LayoutParams(-1, -1));
        indicator = new TextView(context);
        indicator.setGravity(Gravity.CENTER); indicator.setTextSize(13);
        indicator.setTextColor(textColor); indicator.setBackgroundColor(backgroundColor);
        indicator.setVisibility(GONE); indicator.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(indicator, new LayoutParams(-1, dp(44), Gravity.TOP));
    }

    public void setOnRefresh(Runnable callback) { onRefresh = callback; }
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // Native ScrollView requests exclusive touches; retain downward drags at its top.
        if (disallow && eligible && !refreshing && !scroll.canScrollVertically(-1)) return;
        super.requestDisallowInterceptTouchEvent(disallow);
    }
    public boolean isRefreshing() { return refreshing; }
    public void setRefreshing(boolean value) {
        refreshing = value; dragging = false; eligible = false; pull = 0;
        scroll.animate().translationY(0).setDuration(120).start();
        indicator.setText(value ? "Обновляем график…" : "");
        indicator.setVisibility(value ? VISIBLE : GONE);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (refreshing || onRefresh == null) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startX = e.getX(); startY = e.getY(); dragging = false; pull = 0;
            eligible = !scroll.canScrollVertically(-1);
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && eligible && e.getPointerCount() == 1) {
            float dy = e.getY() - startY;
            if (dy > slop && dy > Math.abs(e.getX() - startX) * 1.5f) {
                dragging = true; getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
            if (dy < -slop) eligible = false;
        } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL || e.getActionMasked() == MotionEvent.ACTION_UP) eligible = false;
        return dragging;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        if (e.getPointerCount() > 1 || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            setRefreshing(false); return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            pull = Math.max(0, Math.min(dp(110), (e.getY() - startY) * 0.5f));
            scroll.setTranslationY(pull);
            indicator.setVisibility(VISIBLE);
            indicator.setText(pull >= threshold ? "Отпустите, чтобы обновить" : "Потяните вниз, чтобы обновить");
        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            boolean trigger = pull >= threshold;
            setRefreshing(trigger);
            if (trigger && onRefresh != null) onRefresh.run();
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
