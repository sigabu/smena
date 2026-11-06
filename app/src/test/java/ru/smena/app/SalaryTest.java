package ru.smena.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

public class SalaryTest {
    @Test public void advanceAcceptsRublesKopecksAndClearingWithoutRounding() {
        assertEquals(1500000, Salary.parseAdvance("15000"));
        assertEquals(1500050, Salary.parseAdvance("15 000,50"));
        assertEquals(1500050, Salary.parseAdvance("15000.50"));
        assertEquals(123, Salary.parseAdvance("1,23"));
        assertEquals(100, Salary.parseAdvance("1,"));
        assertEquals(0, Salary.parseAdvance(""));
        assertEquals(0, Salary.parseAdvance("0"));
        assertEquals(99999999999L, Salary.parseAdvance("999999999,99"));
    }

    @Test public void advanceIsSubtractedFromFullPayIncludingOverpayment() {
        assertEquals(3084600, Salary.remaining(141 * 60 + 30, Salary.parseAdvance("15000")));
        assertEquals(0, Salary.remaining(60, Salary.parseAdvance("324")));
        assertEquals(-100, Salary.remaining(60, Salary.parseAdvance("325")));
        assertEquals(-123, Salary.remaining(0, Salary.parseAdvance("1,23")));
        assertThrows(IllegalArgumentException.class, () -> Salary.remaining(60, -1));
    }

    @Test public void advanceRejectsInvalidAndNegativeAmounts() {
        for (String value : new String[]{"-1", "1,234", "1.2.3", "abc", ".", "1000000000"})
            assertThrows(IllegalArgumentException.class, () -> Salary.parseAdvance(value));
    }
    @Test public void exactMinutesAndEmptyMonth() {
        assertEquals(0, Salary.kopecks(0));
        assertEquals(540, Salary.kopecks(1));
        assertEquals(32400, Salary.kopecks(60));
        assertEquals(4584600, Salary.kopecks(141 * 60 + 30));
        assertThrows(IllegalArgumentException.class, () -> Salary.kopecks(-1));
    }

    @Test public void lunchAndManualChangesAffectOnlyChosenEmployeesPay() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        Schedule.Shift original = new Schedule.Shift("Анна", "", date, 540, 1080, false, null);
        Schedule.Shift other = new Schedule.Shift("Борис", "", date, 540, 1080, false, 0);
        Map<String, Schedule.Shift> source = new LinkedHashMap<>(); source.put(original.key(), original); source.put(other.key(), other);
        Schedule.BreakRule rule = Schedule.BreakRule.inferred();
        assertEquals(259200, pay(source, rule)); // Eight paid hours, one-hour automatic lunch.
        Map<String, Schedule.Shift> edits = new LinkedHashMap<>();
        edits.put(original.key(), new Schedule.Shift("Анна", "", date, 540, 1080, false, 45));
        assertEquals(267300, pay(Schedule.merge(source, edits), rule));
        edits.put(original.key(), null);
        assertEquals(0, pay(Schedule.merge(source, edits), rule));
    }

    @Test public void overnightPayStaysInStartingMonthAndArchive() {
        Schedule.Shift night = new Schedule.Shift("Анна", "", LocalDate.of(2026, 11, 30), 1320, 360, true, 30);
        Map<String, Schedule.Shift> saved = new LinkedHashMap<>(); saved.put(night.key(), night);
        Map<String, Schedule.Shift> fresh = new LinkedHashMap<>();
        Schedule.Shift december = new Schedule.Shift("Анна", "", LocalDate.of(2026, 12, 1), 540, 1080, false, null);
        fresh.put(december.key(), december);
        assertEquals(243000, pay(Schedule.refreshMonths(saved, fresh), Schedule.BreakRule.inferred()));
    }

    @Test public void customBreakRuleChangesEstimate() {
        Schedule.Shift shift = new Schedule.Shift("Анна", "", LocalDate.of(2026, 11, 1), 540, 1080, false, null);
        Map<String, Schedule.Shift> source = new LinkedHashMap<>(); source.put(shift.key(), shift);
        assertEquals(267300, pay(source, new Schedule.BreakRule(480, 30, 45)));
    }

    private long pay(Map<String, Schedule.Shift> shifts, Schedule.BreakRule rule) {
        return Salary.kopecks(Schedule.totals(Schedule.select(shifts, YearMonth.of(2026, 11), "Анна"), rule).net);
    }
}
