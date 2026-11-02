package com.example.cardwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * Home screen widget: one row per card with Card, Spend this cycle, Next bill and Last bill.
 */
public class CardWidgetProvider extends AppWidgetProvider {

    private static final int MAX_ROWS = 4;

    private static final int[] ROW = {R.id.row0, R.id.row1, R.id.row2, R.id.row3};
    private static final int[] CARD = {R.id.card0, R.id.card1, R.id.card2, R.id.card3};
    private static final int[] SPEND = {R.id.spend0, R.id.spend1, R.id.spend2, R.id.spend3};
    private static final int[] BILL = {R.id.bill0, R.id.bill1, R.id.bill2, R.id.bill3};
    private static final int[] LAST = {R.id.last0, R.id.last1, R.id.last2, R.id.last3};

    // Badge colours for banks, picked by name so each bank keeps the same colour.
    private static final String[] BADGE_COLOURS = {
            "#1E63B8", "#9E2A2B", "#2E7D32", "#6A1B9A", "#EF6C00", "#00838F", "#455A64",
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        final BroadcastReceiver.PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            SpendTracker.refreshAndPush(app);
            result.finish();
        }).start();
    }

    /** Redraws every placed widget from the saved totals. */
    static void pushToWidgets(Context app) {
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        int[] ids = manager.getAppWidgetIds(new ComponentName(app, CardWidgetProvider.class));
        for (int id : ids) {
            manager.updateAppWidget(id, buildViews(app));
        }
    }

    private static RemoteViews buildViews(Context app) {
        RemoteViews views = new RemoteViews(app.getPackageName(), R.layout.widget_card);

        Intent open = new Intent(app, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        for (int i = 0; i < MAX_ROWS; i++) {
            views.setViewVisibility(ROW[i], View.GONE);
        }

        boolean granted = app.getSharedPreferences(SpendTracker.PREFS, Context.MODE_PRIVATE)
                .getBoolean(SpendTracker.KEY_GRANTED, false);
        if (!granted) {
            showMessage(views, "Tap to allow SMS access in the app");
            return views;
        }

        org.json.JSONArray cards = SpendTracker.loadCards(app);
        if (cards.length() == 0) {
            showMessage(views, "No card SMS found yet");
            return views;
        }

        try {
            for (int i = 0; i < Math.min(cards.length(), MAX_ROWS); i++) {
                JSONObject card = cards.getJSONObject(i);
                String bank = card.optString("bank", "");
                String last4 = card.optString("last4", "");
                String title = (bank.isEmpty() ? "Card" : bank) + "\n•••• " + last4;

                views.setViewVisibility(ROW[i], View.VISIBLE);
                views.setTextViewText(CARD[i], title);
                views.setInt(CARD[i], "setBackgroundColor", badgeColour(bank));
                views.setTextViewText(SPEND[i], SpendTracker.formatMoney(card.optDouble("spendThisCycle", 0), 0));
                views.setTextViewText(BILL[i], SpendTracker.formatDate(card.optLong("nextBill", 0)));
                views.setTextViewText(LAST[i], SpendTracker.formatMoney(card.optDouble("lastBill", 0), 0));
            }
        } catch (org.json.JSONException e) {
            showMessage(views, "Open the app to refresh");
        }
        return views;
    }

    private static void showMessage(RemoteViews views, String message) {
        views.setViewVisibility(ROW[0], View.VISIBLE);
        views.setTextViewText(CARD[0], message);
        views.setTextViewText(SPEND[0], "");
        views.setTextViewText(BILL[0], "");
        views.setTextViewText(LAST[0], "");
    }

    private static int badgeColour(String bank) {
        int index = Math.abs(bank.hashCode()) % BADGE_COLOURS.length;
        return android.graphics.Color.parseColor(BADGE_COLOURS[index]);
    }
}
