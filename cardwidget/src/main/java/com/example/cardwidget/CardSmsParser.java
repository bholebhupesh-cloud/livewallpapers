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

    public enum Type { SPEND, REFUND, PAYMENT, DECLINED, STATEMENT }

    public static final class Event {
        public final Type type;
        public final double amount;
        public final String merchant;
        public final long timeMillis;
        public final String text;
        /** Last four digits of the card, or "" if the message doesn't show them. */
        public final String last4;
        /** Bank name, or "" if it isn't recognised. */
        public final String bank;

        Event(Type type, double amount, String merchant, long timeMillis, String text,
              String last4, String bank) {
            this.type = type;
            this.amount = amount;
            this.merchant = merchant;
            this.timeMillis = timeMillis;
            this.text = text;
            this.last4 = last4;
            this.bank = bank;
        }
    }

    private static final Pattern CARD = Pattern.compile("\\b(card|cc)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANK_ACCOUNT_DEBIT = Pattern.compile(
            "from\\s+(your\\s+)?(a/c|acct|account)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:rs\\.?|inr|\\u20B9)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST4 = Pattern.compile(
            "(?i)(?:ending\\s*(?:with\\s*)?|x{2,}|\\*{2,})\\s*(\\d{4})(?!\\d)");
    private static final Pattern STATEMENT_DUE = Pattern.compile(
            "(?i)(?:total\\s+amount\\s+due|total\\s+due|amount\\s+due|total\\s+outstanding)"
            + "[^0-9]{0,25}?(?:rs\\.?|inr|\\u20B9)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    private static final Pattern MERCHANT = Pattern.compile(
            "\\bat\\s+([A-Za-z0-9&.'\\- ]{2,40}?)(?=\\s+on\\b|\\s+for\\b|\\s+using\\b|\\s+ref\\b|\\s+via\\b|\\s+was\\b|[.,;]|\\s*$)",
            Pattern.CASE_INSENSITIVE);

    /** Bank name to look for in the sender and text, in upper case. */
    private static final String[][] BANKS = {
            {"HDFC", "HDFC"}, {"ICICI", "ICICI"}, {"SBI", "SBI"}, {"AXIS", "Axis"}, {"KOTAK", "Kotak"},
            {"AMERICAN EXPRESS", "Amex"}, {"AMEX", "Amex"}, {"YES BANK", "Yes Bank"}, {"IDFC", "IDFC"},
            {"INDUSIND", "IndusInd"}, {"RBL", "RBL"}, {"STANDARD CHARTERED", "StanChart"},
    };

    private CardSmsParser() {}

    public static Event parse(String body, long timeMillis) {
        return parse(body, timeMillis, "", "");
    }

    public static Event parse(String body, long timeMillis, String last4Filter) {
        return parse(body, timeMillis, "", last4Filter);
    }

    /**
     * Parses one SMS. sender is the SMS address (for bank detection). If last4Filter is
     * not empty, only messages for that card are returned.
     */
    public static Event parse(String body, long timeMillis, String sender, String last4Filter) {
        if (body == null || !CARD.matcher(body).find()) {
            return null;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("debit card") || BANK_ACCOUNT_DEBIT.matcher(body).find()) {
            return null; // bank account or debit card, not a credit card
        }

        String last4 = "";
        Matcher digits = LAST4.matcher(body);
        if (digits.find()) {
            last4 = digits.group(1);
        }
        if (last4Filter != null && !last4Filter.isEmpty() && !last4Filter.equals(last4)) {
            return null;
        }
        String bank = bankOf((sender == null ? "" : sender) + " " + body);

        Matcher due = STATEMENT_DUE.matcher(body);
        if (due.find()) {
            return new Event(Type.STATEMENT, money(due.group(1)), "", timeMillis, body, last4, bank);
        }

        double amount = 0;
        Matcher amountMatcher = AMOUNT.matcher(body);
        if (amountMatcher.find()) {
            amount = money(amountMatcher.group(1));
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
        return new Event(type, amount, merchant, timeMillis, body, last4, bank);
    }

    /** Bank name found in the text, or "" if none of the known banks is mentioned. */
    static String bankOf(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (String[] bank : BANKS) {
            if (upper.contains(bank[0])) {
                return bank[1];
            }
        }
        return "";
    }

    private static double money(String text) {
        return Double.parseDouble(text.replace(",", ""));
    }
}
