package com.example.reelscroll;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Watches Instagram, Facebook and YouTube. When a reel is showing and auto-scroll is on,
 * it swipes to the next reel after the chosen number of seconds. A floating button
 * lets you turn it on or off at any time.
 */
public class ReelScrollService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable swipeTask = this::swipeToNextReel;

    private WindowManager windowManager;
    private LinearLayout overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView bubble;
    private LinearLayout menu;
    private TextView delayValue;

    private boolean scheduled;
    private String currentPackage = "";

    // Drag state for the floating button.
    private int dragStartX;
    private int dragStartY;
    private float touchX;
    private float touchY;
    private boolean dragged;

    @Override
    protected void onServiceConnected() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence name = event.getPackageName();
        String pkg = name == null ? "" : name.toString();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) {
            return;
        }
        if (!ReelSettings.isTarget(this, pkg)) {
            // Left the supported apps, so stop the countdown.
            cancelScheduled();
            currentPackage = "";
            return;
        }
        currentPackage = pkg;
        if (!ReelSettings.isEnabled(this)) {
            cancelScheduled();
            return;
        }
        // A manual swipe restarts the countdown. Other screen changes only start it if it isn't running.
        scheduleSwipe(event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED);
    }

    @Override
    public void onInterrupt() {
        cancelScheduled();
    }

    @Override
    public void onDestroy() {
        cancelScheduled();
        if (windowManager != null && overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (IllegalArgumentException ignored) {
                // Already removed.
            }
        }
        super.onDestroy();
    }

    private void scheduleSwipe(boolean restart) {
        if (scheduled && !restart) {
            return;
        }
        handler.removeCallbacks(swipeTask);
        handler.postDelayed(swipeTask, ReelSettings.delaySeconds(this) * 1000L);
        scheduled = true;
    }

    private void cancelScheduled() {
        handler.removeCallbacks(swipeTask);
        scheduled = false;
    }

    private void swipeToNextReel() {
        scheduled = false;
        if (!ReelSettings.isEnabled(this) || !ReelSettings.isTarget(this, currentPackage)) {
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f;
        Path path = new Path();
        path.moveTo(x, dm.heightPixels * 0.80f);
        path.lineTo(x, dm.heightPixels * 0.25f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 300))
                .build();
        dispatchGesture(gesture, null, null);
        scheduleSwipe(true); // countdown for the next reel
    }

    // ---- Floating button and menu ----

    private void buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);

        bubble = new TextView(this);
        bubble.setText("▶");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(20);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFE53935);
        bubble.setBackground(circle);
        bubble.setOnTouchListener(this::onBubbleTouch);
        overlay.addView(bubble, new LinearLayout.LayoutParams(dp(56), dp(56)));

        menu = buildMenu();
        menu.setVisibility(View.GONE);
        overlay.addView(menu);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = 0;
        overlayParams.y = dp(200);
        windowManager.addView(overlay, overlayParams);
        updateBubble();
    }

    private boolean onBubbleTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = overlayParams.x;
                dragStartY = overlayParams.y;
                touchX = e.getRawX();
                touchY = e.getRawY();
                dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - touchX;
                float dy = e.getRawY() - touchY;
                if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                    dragged = true;
                }
                overlayParams.x = dragStartX + (int) dx;
                overlayParams.y = dragStartY + (int) dy;
                windowManager.updateViewLayout(overlay, overlayParams);
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragged) {
                    menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
                return true;
            default:
                return false;
        }
    }

    private LinearLayout buildMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(dp(14));
        panel.setColor(0xEE1F2937);
        box.setBackground(panel);

        box.addView(text("Reel auto-scroll", 16, true));

        addSwitch(box, "Auto-scroll on", ReelSettings.isEnabled(this), (button, on) -> {
            ReelSettings.setEnabled(this, on);
            if (!on) {
                cancelScheduled();
            }
            updateBubble();
        });

        LinearLayout delayRow = new LinearLayout(this);
        delayRow.setOrientation(LinearLayout.HORIZONTAL);
        delayRow.setGravity(Gravity.CENTER_VERTICAL);
        delayRow.addView(text("Seconds per reel", 14, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView minus = pill("−");
        delayValue = text(String.valueOf(ReelSettings.delaySeconds(this)), 14, true);
        delayValue.setGravity(Gravity.CENTER);
        TextView plus = pill("+");
        minus.setOnClickListener(v -> changeDelay(-5));
        plus.setOnClickListener(v -> changeDelay(5));
        delayRow.addView(minus);
        delayRow.addView(delayValue, new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));
        delayRow.addView(plus);
        box.addView(delayRow);

        box.addView(text("Work in", 13, true));
        appSwitch(box, "Instagram Reels", ReelSettings.INSTAGRAM);
        appSwitch(box, "Facebook Reels", ReelSettings.FACEBOOK);
        appSwitch(box, "YouTube Shorts", ReelSettings.YOUTUBE);
        return box;
    }

    private void appSwitch(LinearLayout box, String label, String pkg) {
        addSwitch(box, label, ReelSettings.isTarget(this, pkg), (button, on) -> {
            ReelSettings.setApp(this, pkg, on);
            if (!on && pkg.equals(currentPackage)) {
                cancelScheduled();
            }
        });
    }

    private void changeDelay(int change) {
        int next = ReelSettings.delaySeconds(this) + change;
        ReelSettings.setDelaySeconds(this, next);
        int saved = ReelSettings.delaySeconds(this);
        delayValue.setText(String.valueOf(saved));
        if (scheduled) {
            scheduleSwipe(true); // apply the new delay straight away
        }
    }

    private void updateBubble() {
        bubble.setAlpha(ReelSettings.isEnabled(this) ? 1f : 0.45f);
    }

    private void addSwitch(LinearLayout parent, String label, boolean checked,
                           CompoundButton.OnCheckedChangeListener listener) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        parent.addView(sw);
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sizeSp);
        view.setPadding(dp(4), dp(6), dp(4), dp(6));
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private TextView pill(String label) {
        TextView view = text(label, 18, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(2), dp(12), dp(4));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(0xFF374151);
        view.setBackground(shape);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
