package com.example.cardwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** Home screen widget showing this month's credit card spend and the latest card alert. */
public class CardWidgetProvider extends AppWidgetProvider {

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

        SharedPreferences prefs = app.getSharedPreferences(SpendTracker.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SpendTracker.KEY_GRANTED, false)) {
            views.setTextViewText(R.id.tv_amount, "Tap to allow SMS");
            views.setTextViewText(R.id.tv_detail, "Needed to read your card alerts");
            views.setTextViewText(R.id.tv_status, "");
            return views;
        }

        double spend = prefs.getLong(SpendTracker.KEY_SPEND_PAISE, 0) / 100.0;
        int count = prefs.getInt(SpendTracker.KEY_COUNT, 0);
        String last = prefs.getString(SpendTracker.KEY_LAST, "");
        long updated = prefs.getLong(SpendTracker.KEY_UPDATED, 0);

        views.setTextViewText(R.id.tv_amount, SpendTracker.formatMoney(spend));
        views.setTextViewText(R.id.tv_detail, count + (count == 1 ? " transaction" : " transactions") + " this month");
        views.setTextViewText(R.id.tv_status, last.isEmpty() ? "No card alerts yet" : "Latest: " + last);
        views.setTextViewText(R.id.tv_updated, updated == 0 ? "" : "Updated " + SpendTracker.formatTime(updated));
        return views;
    }
}
