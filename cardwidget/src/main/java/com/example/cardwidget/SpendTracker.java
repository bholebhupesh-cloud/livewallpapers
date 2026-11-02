package com.example.cardwidget;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads recent bank SMS on the phone, finds every credit card in them, and works out each
 * card's totals. The results are saved for the app and the widget to show.
 */
public final class SpendTracker {

    static final String PREFS = "card_spend";
    static final String KEY_GRANTED = "granted";
    static final String KEY_CARDS = "cards_json";
    static final String KEY_BILLING = "billing_json";
    static final String KEY_UPDATED = "updated";

    // Enough history for the previous billing cycle, plus a margin.
    private static final long LOOKBACK_MS = 70L * 24 * 60 * 60 * 1000;

    private SpendTracker() {}

    static boolean hasSmsAccess(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads the SMS inbox, recalculates every card, saves the results, then updates the widget. */
    static void refreshAndPush(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean granted = hasSmsAccess(app);
        JSONArray cards = new JSONArray();

        if (granted) {
            try {
                Map<String, List<CardSmsParser.Event>> byCard = readCardEvents(app);
                JSONObject billing = readBilling(prefs);

                // Include cards you've set a billing day for even if no SMS mentions them yet.
                Iterator<String> keys = billing.keys();
                while (keys.hasNext()) {
                    String last4 = keys.next();
                    if (!byCard.containsKey(last4)) {
                        byCard.put(last4, new ArrayList<>());
                    }
                }

                long now = System.currentTimeMillis();
                for (Map.Entry<String, List<CardSmsParser.Event>> entry : byCard.entrySet()) {
                    String last4 = entry.getKey();
                    List<CardSmsParser.Event> events = entry.getValue();
                    String bank = "";
                    for (CardSmsParser.Event event : events) {
                        if (!event.bank.isEmpty()) {
                            bank = event.bank;
                            break; // events are newest first
                        }
                    }
                    int day = billing.optInt(last4, 1);
                    CardCycles.Summary summary = CardCycles.summarize(last4, bank, events, day, now);
                    cards.put(toJson(summary));
                }
            } catch (SecurityException e) {
                granted = false; // permission was removed while running
            } catch (JSONException e) {
                granted = false;
            }
        }

        prefs.edit()
                .putBoolean(KEY_GRANTED, granted)
                .putString(KEY_CARDS, cards.toString())
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply();

        CardWidgetProvider.pushToWidgets(app);
    }

    /** Saves the billing day (1-28) for one card, then refreshes everything. */
    static void setBillingDay(Context app, String last4, int day) throws JSONException {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject billing = readBilling(prefs);
        billing.put(last4, day);
        prefs.edit().putString(KEY_BILLING, billing.toString()).apply();
        refreshAndPush(app);
    }

    /** The saved cards, as a JSON array. Empty if nothing has been read yet. */
    static JSONArray loadCards(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            return new JSONArray(prefs.getString(KEY_CARDS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static String formatMoney(double amount, int fractionDigits) {
        NumberFormat format = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        format.setMinimumFractionDigits(fractionDigits);
        format.setMaximumFractionDigits(fractionDigits);
        return format.format(amount);
    }

    static String formatDate(long millis) {
        return new SimpleDateFormat("dd MMM", Locale.ENGLISH).format(new Date(millis));
    }

    static String formatTime(long millis) {
        return new SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH).format(new Date(millis));
    }

    private static Map<String, List<CardSmsParser.Event>> readCardEvents(Context app) {
        Map<String, List<CardSmsParser.Event>> byCard = new HashMap<>();
        long since = System.currentTimeMillis() - LOOKBACK_MS;
        Cursor cursor = null;
        try {
            cursor = app.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.ADDRESS},
                    Telephony.Sms.DATE + " >= ?",
                    new String[]{String.valueOf(since)},
                    Telephony.Sms.DATE + " DESC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    CardSmsParser.Event event = CardSmsParser.parse(
                            cursor.getString(0), cursor.getLong(1), cursor.getString(2), "");
                    if (event == null || event.last4.isEmpty()) {
                        continue; // not a card message, or the card number isn't shown
                    }
                    List<CardSmsParser.Event> events = byCard.get(event.last4);
                    if (events == null) {
                        events = new ArrayList<>();
                        byCard.put(event.last4, events);
                    }
                    events.add(event);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return byCard;
    }

    private static JSONObject readBilling(SharedPreferences prefs) {
        try {
            return new JSONObject(prefs.getString(KEY_BILLING, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject toJson(CardCycles.Summary s) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("last4", s.last4);
        json.put("bank", s.bank);
        json.put("billingDay", s.billingDay);
        json.put("spendThisCycle", s.spendThisCycle);
        json.put("lastBill", s.lastBill);
        json.put("nextBill", s.nextBillMillis);
        return json;
    }
}
