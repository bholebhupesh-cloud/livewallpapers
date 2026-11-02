package com.example.cardwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

/** Runs when a new SMS arrives, so the widget updates as soon as a card alert comes in. */
public class SmsReceiver extends BroadcastReceiver {

    // Give the SMS a moment to be written to the inbox before reading it back.
    private static final long INBOX_DELAY_MS = 1500L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }
        final BroadcastReceiver.PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Thread.sleep(INBOX_DELAY_MS);
            } catch (InterruptedException ignored) {
                // Carry on regardless; the inbox read below is still correct.
            }
            SpendTracker.refreshAndPush(app);
            result.finish();
        }).start();
    }
}
