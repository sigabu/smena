package ru.smena.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScheduleTest {
    private final Schedule.BreakRule rule = Schedule.BreakRule.inferred();
    private Schedule.Shift shift(int day, String start, String end) {
        return new Schedule.Shift("Сотрудник", "", LocalDate.of(2026, 11, day), Schedule.parseTime(start), Schedule.parseTime(end), false, null);
    }

    @Test public void photoClothingTotalIs108HoursAnd12LunchHours() {
        List<Schedule.Shift> shifts = new ArrayList<>();
        for (int day : new int[]{1,2,8,9,15,16,22,23,29,30}) shifts.add(shift(day, "10:00", "20:00"));
        for (int day : new int[]{6,13,20,27}) shifts.add(shift(day, "17:00", "22:00"));
        Schedule.Totals total = Schedule.totals(shifts, rule);
        assertEquals(108 * 60, total.net); assertEquals(12 * 60, total.lunch); assertEquals(120 * 60, total.gross);
    }

    @Test public void photoMixedShoeShiftsRequireFullHourLunchForEightHourShift() {
        List<Schedule.Shift> shifts = new ArrayList<>();
        shifts.add(shift(1, "14:00", "22:00")); shifts.add(shift(6, "09:30", "19:00"));
        for (int day : new int[]{2,9,16,23,30}) shifts.add(shift(day, "09:30", "22:00"));
        for (int day : new int[]{3,5,10,12,13,17,19,20,24,26,27}) shifts.add(shift(day, "09:30", "17:00"));
        Schedule.Totals total = Schedule.totals(shifts, rule);
        assertEquals(18, total.count); assertEquals(9750, total.gross);
        assertEquals(150 * 60, total.net); assertEquals(750, total.lunch);
    }

    @Test public void photoServiceTotalIs160Hours() {
        List<Schedule.Shift> shifts = new ArrayList<>();
        for (int i = 1; i <= 20; i++) shifts.add(shift(i, "10:00", "19:00"));
        Schedule.Totals t = Schedule.totals(shifts, rule); assertEquals(9600, t.net); assertEquals(1200, t.lunch);
    }

    @Test public void halfHourLunchBelowEightHoursAndFullHourAtEight() {
        assertEquals(30, rule.minutes(450)); assertEquals(30, rule.minutes(479));
        assertEquals(60, rule.minutes(480)); assertEquals(60, rule.minutes(750));
    }

    @Test public void explicitZeroAndCustomLunchOverrideRule() {
        Schedule.Shift s = new Schedule.Shift("А", "", LocalDate.of(2026,11,1), 600, 1200, false, 0);
        assertEquals(600, s.net(rule));
        s = new Schedule.Shift("А", "", s.date, 600, 1200, false, 45); assertEquals(555, s.net(rule));
    }

    @Test public void nightShiftBelongsToStartingMonth() {
        Schedule.Shift s = new Schedule.Shift("А", "", LocalDate.of(2026,11,30), 1320, 360, true, 30);
        assertEquals(480, s.gross()); assertEquals(450, s.net(rule));
        Map<String, Schedule.Shift> map = new LinkedHashMap<>(); map.put(s.key(), s);
        assertEquals(1, Schedule.select(map, YearMonth.of(2026,11), "А").size());
        assertEquals(0, Schedule.select(map, YearMonth.of(2026,12), "А").size());
    }

    @Test public void deletionAndEditSurviveSourceRefresh() {
        Schedule.Shift initial = shift(1, "09:30", "19:00"), second = shift(2, "09:30", "19:00");
        Map<String, Schedule.Shift> source = new LinkedHashMap<>(); source.put(initial.key(), initial); source.put(second.key(), second);
        Map<String, Schedule.Shift> edits = new LinkedHashMap<>(); edits.put(initial.key(), null);
        Schedule.Shift edited = shift(2, "10:00", "17:00"); edits.put(edited.key(), edited);
        source.put(initial.key(), shift(1, "08:00", "16:00"));
        Map<String, Schedule.Shift> merged = Schedule.merge(source, edits);
        assertFalse(merged.containsKey(initial.key())); assertEquals(600, merged.get(second.key()).start);
        assertEquals(2, source.size());
    }

    @Test public void employeeFilterCannotIncludeSomeoneElse() {
        Map<String, Schedule.Shift> source = new LinkedHashMap<>(); Schedule.Shift a = shift(1, "09:00", "18:00");
        Schedule.Shift b = new Schedule.Shift("Другой", "", a.date, 600, 1200, false, null);
        source.put(a.key(), a); source.put(b.key(), b);
        assertEquals(1, Schedule.select(source, YearMonth.of(2026,11), a.employee).size());
        assertEquals(0, Schedule.select(source, YearMonth.of(2026,11), "").size());
    }

    @Test public void invalidDurationsDatesAndLunchesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> shift(1, "10:00", "09:00"));
        assertThrows(IllegalArgumentException.class, () -> shift(1, "10:00", "10:00"));
        assertThrows(IllegalArgumentException.class, () -> new Schedule.Shift("А", "", LocalDate.now(), 600, 1200, true, null));
        assertThrows(IllegalArgumentException.class, () -> new Schedule.Shift("А", "", LocalDate.now(), 600, 660, false, 61));
        assertThrows(IllegalArgumentException.class, () -> Schedule.parseTime("24:00"));
        assertThrows(IllegalArgumentException.class, () -> Schedule.parseTime("09:60"));
        assertThrows(IllegalArgumentException.class, () -> Schedule.parseDate("31.02.2026"));
        assertEquals(LocalDate.of(2026,11,5), Schedule.parseDate("05.11.2026"));
    }
}
