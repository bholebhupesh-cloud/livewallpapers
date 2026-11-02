package com.example.cardwidget;

import java.util.Calendar;
import java.util.List;

/**
 * Works out one card's billing cycle totals from its SMS events. Plain Java, so it can be
 * tested on a computer.
 *
 * Cycle C starts on the billing day. Spend since C is "spend this cycle" and resets to 0
 * on the billing day. The last bill is the statement generated on C for the period before
 * it. Payments made since C reduce it, so it shows 0 once paid.
 */
public final class CardCycles {

    public static final class Summary {
        public final String last4;
        public final String bank;
        public final int billingDay;
        public final double spendThisCycle;
        public final double lastBill;
        public final long nextBillMillis;

        Summary(String last4, String bank, int billingDay, double spendThisCycle,
                double lastBill, long nextBillMillis) {
            this.last4 = last4;
            this.bank = bank;
            this.billingDay = billingDay;
            this.spendThisCycle = spendThisCycle;
            this.lastBill = lastBill;
            this.nextBillMillis = nextBillMillis;
        }
    }

    private static final long THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000;

    private CardCycles() {}

    public static Summary summarize(String last4, String bank, List<CardSmsParser.Event> events,
                                    int billingDay, long now) {
        int day = Math.max(1, Math.min(28, billingDay));
        long cycleStart = billDate(now, day, dayOfMonth(now) >= day ? 0 : -1);
        long previousStart = billDate(cycleStart, day, -1);
        long nextBill = billDate(cycleStart, day, 1);

        double spendThisCycle = 0;
        double spendPrevious = 0;
        double paid = 0;
        double statement = -1;
        long statementTime = Long.MIN_VALUE;

        for (CardSmsParser.Event event : events) {
            long t = event.timeMillis;
            if (t > now) {
                continue;
            }
            double signed = event.type == CardSmsParser.Type.REFUND ? -event.amount
                    : event.type == CardSmsParser.Type.SPEND ? event.amount : 0;
            if (t >= cycleStart) {
                spendThisCycle += signed;
                if (event.type == CardSmsParser.Type.PAYMENT) {
                    paid += event.amount;
                }
            } else if (t >= previousStart) {
                spendPrevious += signed;
            }
            // The bank's own "total amount due" SMS, sent around the billing day.
            if (event.type == CardSmsParser.Type.STATEMENT
                    && t >= cycleStart - THREE_DAYS_MS && t < cycleStart + THREE_DAYS_MS
                    && t > statementTime) {
                statement = event.amount;
                statementTime = t;
            }
        }

        double statementAmount = statement >= 0 ? statement : spendPrevious;
        double lastBill = Math.max(0, statementAmount - paid);
        return new Summary(last4, bank, day, Math.max(0, spendThisCycle), lastBill, nextBill);
    }

    private static int dayOfMonth(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.DAY_OF_MONTH);
    }

    /** Midnight on the billing day, offset by whole months from the reference time. */
    static long billDate(long reference, int billingDay, int monthOffset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(reference);
        c.add(Calendar.MONTH, monthOffset);
        c.set(Calendar.DAY_OF_MONTH, Math.min(billingDay, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
