package com.example.cardwidget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.TextView;

/** Lets you allow SMS access, and shows the same totals as the widget. */
public class MainActivity extends Activity {

    private static final int REQUEST_SMS = 1;

    private TextView summary;
    private Button allowButton;
    private EditText last4Input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        summary = findViewById(R.id.tv_summary);
        allowButton = findViewById(R.id.btn_allow);

        allowButton.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, REQUEST_SMS));
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshInBackground());
        last4Input = findViewById(R.id.et_last4);
        last4Input.setText(getSharedPreferences(SpendTracker.PREFS, MODE_PRIVATE)
                .getString(SpendTracker.KEY_LAST4, ""));
        findViewById(R.id.btn_save_last4).setOnClickListener(v -> saveLast4());
        findViewById(R.id.btn_add_widget).setOnClickListener(v -> addWidget());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SpendTracker.hasSmsAccess(this)) {
            refreshInBackground();
        } else {
            showSummary();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshInBackground();
    }

    /** Places the widget on the home screen directly, without going through the widget picker. */
    private void addWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, CardWidgetProvider.class);
        if (manager.isRequestPinAppWidgetSupported()) {
            manager.requestPinAppWidget(provider, null, null);
        } else {
            Toast.makeText(this, "Long-press the home screen, then tap Widgets", Toast.LENGTH_LONG).show();
        }
    }

    private void saveLast4() {
        String digits = last4Input.getText().toString().trim();
        if (!digits.isEmpty() && !digits.matches("\\d{4}")) {
            Toast.makeText(this, "Enter exactly 4 digits", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(SpendTracker.PREFS, MODE_PRIVATE).edit()
                .putString(SpendTracker.KEY_LAST4, digits).apply();
        Toast.makeText(this, digits.isEmpty() ? "Counting all cards" : "Saved", Toast.LENGTH_SHORT).show();
        refreshInBackground();
    }

    private void refreshInBackground() {
        new Thread(() -> {
            SpendTracker.refreshAndPush(getApplicationContext());
            runOnUiThread(this::showSummary);
        }).start();
    }

    private void showSummary() {
        boolean granted = SpendTracker.hasSmsAccess(this);
        allowButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            summary.setText("This app reads your bank card SMS to add up your spending. "
                    + "Nothing is uploaded; everything stays on this phone.\n\nTap \"Allow SMS access\" to start.");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(SpendTracker.PREFS, MODE_PRIVATE);
        double spend = prefs.getLong(SpendTracker.KEY_SPEND_PAISE, 0) / 100.0;
        int count = prefs.getInt(SpendTracker.KEY_COUNT, 0);
        String last = prefs.getString(SpendTracker.KEY_LAST, "");
        long updated = prefs.getLong(SpendTracker.KEY_UPDATED, 0);

        StringBuilder text = new StringBuilder();
        text.append("Credit card spend this month\n")
                .append(SpendTracker.formatMoney(spend))
                .append("  (").append(count).append(count == 1 ? " transaction)" : " transactions)")
                .append("\n\n")
                .append(last.isEmpty() ? "No card alerts found this month." : "Latest: " + last);
        if (updated != 0) {
            text.append("\n\nUpdated ").append(SpendTracker.formatTime(updated));
        }
        String digits = prefs.getString(SpendTracker.KEY_LAST4, "");
        text.append("\n\n").append(digits.isEmpty()
                ? "Counting all card SMS. Enter your card's last 4 digits to count only that card."
                : "Counting only the card ending " + digits + ".");
        summary.setText(text.toString());
    }
}
