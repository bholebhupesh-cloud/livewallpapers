package com.example.cardwidget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

/** Shows every detected card, lets you set each card's billing day, and places the widget. */
public class MainActivity extends Activity {

    private static final int REQUEST_SMS = 1;

    private TextView summary;
    private Button allowButton;
    private EditText last4Input;
    private EditText dayInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        summary = findViewById(R.id.tv_summary);
        allowButton = findViewById(R.id.btn_allow);
        last4Input = findViewById(R.id.et_last4);
        dayInput = findViewById(R.id.et_day);

        allowButton.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, REQUEST_SMS));
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshInBackground());
        findViewById(R.id.btn_add_widget).setOnClickListener(v -> addWidget());
        findViewById(R.id.btn_save_billing).setOnClickListener(v -> saveBillingDay());
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

    private void saveBillingDay() {
        String last4 = last4Input.getText().toString().trim();
        String dayText = dayInput.getText().toString().trim();
        if (!last4.matches("\\d{4}")) {
            Toast.makeText(this, "Enter the 4 digits of the card", Toast.LENGTH_SHORT).show();
            return;
        }
        int day;
        try {
            day = Integer.parseInt(dayText);
        } catch (NumberFormatException e) {
            day = 0;
        }
        if (day < 1 || day > 28) {
            Toast.makeText(this, "Billing day must be between 1 and 28", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SpendTracker.setBillingDay(getApplicationContext(), last4, day);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, "Could not save", Toast.LENGTH_SHORT).show();
        }
        showSummary();
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

        org.json.JSONArray cards = SpendTracker.loadCards(this);
        if (cards.length() == 0) {
            summary.setText("No credit card SMS found in the last 70 days.");
            return;
        }

        StringBuilder text = new StringBuilder();
        try {
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                String bank = card.optString("bank", "Card");
                text.append(bank).append(" •••• ").append(card.optString("last4"))
                        .append("\n  Spend this cycle: ")
                        .append(SpendTracker.formatMoney(card.optDouble("spendThisCycle", 0), 2))
                        .append("\n  Billing day: ").append(card.optInt("billingDay", 1))
                        .append(" • Next bill: ").append(SpendTracker.formatDate(card.optLong("nextBill", 0)))
                        .append("\n  Last bill due: ")
                        .append(SpendTracker.formatMoney(card.optDouble("lastBill", 0), 2))
                        .append("\n\n");
            }
        } catch (JSONException e) {
            text.append("Could not read saved cards.");
        }
        summary.setText(text.toString().trim());
    }
}
