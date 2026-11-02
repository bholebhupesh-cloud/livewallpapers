package com.example.cardwidget;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Reads this month's bank SMS on the phone, works out the card totals, and refreshes the widget. */
public final class SpendTracker {

    static final String PREFS = "card_spend";
    static final String KEY_GRANTED = "granted";
    static final String KEY_SPEND_PAISE = "spend_paise";
    static final String KEY_COUNT = "count";
    static final String KEY_LAST = "last";
    static final String KEY_UPDATED = "updated";
    static final String KEY_LAST4 = "last4";

    private SpendTracker() {}

    static boolean hasSmsAccess(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Recalculates the totals from the SMS inbox, saves them, then updates every widget. */
    static void refreshAndPush(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean granted = hasSmsAccess(app);
        long spendPaise = 0;
        int count = 0;
        String last = "";
        String last4 = prefs.getString(KEY_LAST4, "");

        if (granted) {
            Calendar monthStart = Calendar.getInstance();
            monthStart.set(Calendar.DAY_OF_MONTH, 1);
            monthStart.set(Calendar.HOUR_OF_DAY, 0);
            monthStart.set(Calendar.MINUTE, 0);
            monthStart.set(Calendar.SECOND, 0);
            monthStart.set(Calendar.MILLISECOND, 0);

            Cursor cursor = null;
            try {
                cursor = app.getContentResolver().query(
                        Telephony.Sms.Inbox.CONTENT_URI,
                        new String[]{Telephony.Sms.BODY, Telephony.Sms.DATE},
                        Telephony.Sms.DATE + " >= ?",
                        new String[]{String.valueOf(monthStart.getTimeInMillis())},
                        Telephony.Sms.DATE + " DESC");
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        CardSmsParser.Event event =
                                CardSmsParser.parse(cursor.getString(0), cursor.getLong(1), last4);
                        if (event == null) {
                            continue;
                        }
                        if (last.isEmpty()) {
                            last = describe(event); // newest card message first
                        }
                        long paise = Math.round(event.amount * 100);
                        if (event.type == CardSmsParser.Type.SPEND) {
                            spendPaise += paise;
                            count++;
                        } else if (event.type == CardSmsParser.Type.REFUND) {
                            spendPaise -= paise;
                        }
                    }
                }
            } catch (SecurityException e) {
                granted = false; // permission was revoked while running
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        prefs.edit()
                .putBoolean(KEY_GRANTED, granted)
                .putLong(KEY_SPEND_PAISE, spendPaise)
                .putInt(KEY_COUNT, count)
                .putString(KEY_LAST, last)
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply();

        CardWidgetProvider.pushToWidgets(app);
    }

    static String formatMoney(double amount) {
        return NumberFormat.getCurrencyInstance(new Locale("en", "IN")).format(amount);
    }

    static String formatTime(long millis) {
        return new SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH).format(new Date(millis));
    }

    private static String describe(CardSmsParser.Event event) {
        String when = formatTime(event.timeMillis);
        String where = event.merchant.isEmpty() ? "" : " at " + event.merchant;
        switch (event.type) {
            case SPEND:
                return "Spent " + formatMoney(event.amount) + where + " · " + when;
            case REFUND:
                return "Refund " + formatMoney(event.amount) + where + " · " + when;
            case PAYMENT:
                return "Card payment " + formatMoney(event.amount) + " · " + when;
            case DECLINED:
                return "Declined " + formatMoney(event.amount) + where + " · " + when;
            default:
                return "";
        }
    }
}
