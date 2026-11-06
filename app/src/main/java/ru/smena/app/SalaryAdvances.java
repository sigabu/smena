package ru.smena.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.YearMonth;

/** Payments belong to a person and month, independently of schedule imports or resets. */
public final class SalaryAdvances {
    private final SharedPreferences preferences;
    public SalaryAdvances(Context context) { this(context, "salary_advances"); }
    SalaryAdvances(Context context, String name) { preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE); }

    public long get(String employee, YearMonth month) { return preferences.getLong(key(employee, month), 0); }
    public void set(String employee, YearMonth month, long kopecks) {
        if (kopecks < 0 || kopecks > 99999999999L) throw new IllegalArgumentException("Некорректная сумма аванса");
        if (get(employee, month) == kopecks) return;
        preferences.edit().putLong(key(employee, month), kopecks).apply();
    }
    private String key(String employee, YearMonth month) { return month + "\t" + employee.trim(); }
}
