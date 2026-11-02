package com.example.cardwidget;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a bank SMS into a credit card event. Plain Java with no Android imports,
 * so it can be tested on a computer. Patterns are tuned for common Indian bank
 * alert wording and may need adjusting for your bank's exact messages.
 */
public final class CardSmsParser {

    public enum Type { SPEND, REFUND, PAYMENT, DECLINED }

    public static final class Event {
        public final Type type;
        public final double amount;
        public final String merchant;
        public final long timeMillis;
        public final String text;

        Event(Type type, double amount, String merchant, long timeMillis, String text) {
            this.type = type;
            this.amount = amount;
            this.merchant = merchant;
            this.timeMillis = timeMillis;
            this.text = text;
        }
    }

    private static final Pattern CARD = Pattern.compile(
            "\\b(card|cc)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANK_ACCOUNT_DEBIT = Pattern.compile(
            "from\\s+(your\\s+)?(a/c|acct|account)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:rs\\.?|inr|\\u20B9)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERCHANT = Pattern.compile(
            "\\bat\\s+([A-Za-z0-9&.'\\- ]{2,40}?)(?=\\s+on\\b|\\s+for\\b|\\s+using\\b|\\s+ref\\b|\\s+via\\b|\\s+was\\b|[.,;]|\\s*$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Like parse(), but only accepts messages that mention the given last four card digits,
     * written as "ending 1234", "XX1234" or "****1234". An empty last4 accepts any card.
     */
    public static Event parse(String body, long timeMillis, String last4) {
        if (last4 != null && !last4.isEmpty()) {
            Pattern cardDigits = Pattern.compile(
                    "(?i)(?:ending\\s*(?:with\\s*)?|x{2,}|\\*{2,})\\s*" + Pattern.quote(last4) + "(?!\\d)");
            if (body == null || !cardDigits.matcher(body).find()) {
                return null;
            }
        }
        return parse(body, timeMillis);
    }

    private CardSmsParser() {}

    /** Returns the card event in this message, or null if it is not a card transaction alert. */
    public static Event parse(String body, long timeMillis) {
        if (body == null || !CARD.matcher(body).find()) {
            return null;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("debit card") || BANK_ACCOUNT_DEBIT.matcher(body).find()) {
            return null; // bank account or debit card, not a credit card
        }

        double amount = 0;
        Matcher amountMatcher = AMOUNT.matcher(body);
        if (amountMatcher.find()) {
            amount = Double.parseDouble(amountMatcher.group(1).replace(",", ""));
        }

        Type type;
        if (lower.contains("declin") || lower.contains("failed")) {
            type = Type.DECLINED;
        } else if (lower.contains("refund") || lower.contains("reversal")) {
            type = Type.REFUND;
        } else if (lower.contains("payment") && (lower.contains("received") || lower.contains("credited")
                || lower.contains("successful") || lower.contains("towards") || lower.contains("thank"))) {
            type = Type.PAYMENT;
        } else if (lower.contains("spent") || lower.contains("debited") || lower.contains("charged")
                || lower.contains("txn") || lower.contains("transaction") || lower.contains("purchase")) {
            type = Type.SPEND;
        } else {
            return null;
        }

        // Declined alerts may carry no amount; every other type needs one (skips OTP messages).
        if (amount == 0 && type != Type.DECLINED) {
            return null;
        }

        String merchant = "";
        Matcher merchantMatcher = MERCHANT.matcher(body);
        if (merchantMatcher.find()) {
            merchant = merchantMatcher.group(1).trim();
        }
        return new Event(type, amount, merchant, timeMillis, body);
    }
}
