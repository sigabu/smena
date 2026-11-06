package ru.smena.app;

import java.math.BigDecimal;

/** Take-home pay at the supplied after-tax rate, calculated from exact paid minutes. */
public final class Salary {
    public static final int HOURLY_RUBLES = 324;
    private Salary() {}

    public static long kopecks(int netMinutes) {
        if (netMinutes < 0) throw new IllegalArgumentException("Рабочее время не может быть отрицательным");
        return netMinutes * (long) HOURLY_RUBLES * 100 / 60;
    }

    public static long parseAdvance(String text) {
        String value = text.trim().replace(" ", "").replace("\u00a0", "").replace("\u202f", "").replace(',', '.');
        if (value.isEmpty()) return 0;
        if (!value.matches("\\d{1,9}(?:\\.\\d{0,2})?"))
            throw new IllegalArgumentException("Введите сумму от 0 до 999 999 999,99 ₽, до двух знаков после запятой");
        return new BigDecimal(value).movePointRight(2).longValueExact();
    }
    public static long remaining(int netMinutes, long advanceKopecks) {
        if (advanceKopecks < 0) throw new IllegalArgumentException("Аванс не может быть отрицательным");
        return kopecks(netMinutes) - advanceKopecks;
    }
}
