package ru.smena.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

public class HistoryTest {
    private Schedule.Shift shift(String employee, String date, int end) {
        return new Schedule.Shift(employee, "", LocalDate.parse(date), 540, end, false, 60);
    }
    private Map<String, Schedule.Shift> rows(Schedule.Shift... shifts) {
        Map<String, Schedule.Shift> result = new LinkedHashMap<>();
        for (Schedule.Shift s : shifts) result.put(s.key(), s);
        return result;
    }

    @Test public void switchingToDecemberKeepsNovemberAndItsTotal() {
        Map<String, Schedule.Shift> november = rows(shift("А", "2026-11-01", 1080), shift("А", "2026-11-02", 1140));
        Map<String, Schedule.Shift> result = Schedule.refreshMonths(november, rows(shift("А", "2026-12-01", 1020)));
        assertEquals(3, result.size());
        assertEquals(17 * 60, Schedule.totals(Schedule.select(result, YearMonth.of(2026,11), "А"), Schedule.BreakRule.inferred()).net);
        assertEquals(7 * 60, Schedule.totals(Schedule.select(result, YearMonth.of(2026,12), "А"), Schedule.BreakRule.inferred()).net);
        assertEquals(2, november.size());
    }

    @Test public void refreshReplacesOnlyTheMonthPresentAndRemovesMissingShiftsInIt() {
        Schedule.Shift archived = shift("А", "2026-11-01", 1080);
        Schedule.Shift changed = shift("А", "2026-12-01", 1200);
        Schedule.Shift omitted = shift("А", "2026-12-02", 1080);
        Schedule.Shift otherEmployee = shift("Б", "2026-12-01", 1080);
        Map<String, Schedule.Shift> result = Schedule.refreshMonths(rows(archived, changed, omitted, otherEmployee),
                rows(shift("А", "2026-12-01", 1020)));
        assertEquals(2, result.size()); assertSame(archived, result.get(archived.key()));
        assertEquals(1020, result.get(changed.key()).end);
        assertFalse(result.containsKey(omitted.key())); assertFalse(result.containsKey(otherEmployee.key()));
    }

    @Test public void multipleMonthsUpdateWithoutTouchingMonthsBetweenThem() {
        Schedule.Shift november = shift("А", "2026-11-01", 1080), december = shift("А", "2026-12-01", 1080);
        Map<String, Schedule.Shift> result = Schedule.refreshMonths(rows(november, december),
                rows(shift("А", "2026-11-01", 1200), shift("А", "2027-01-01", 1140)));
        assertEquals(3, result.size()); assertSame(december, result.get(december.key()));
        assertEquals(1200, result.get(november.key()).end);
    }

    @Test public void sameMonthOfDifferentYearsDoesNotEraseHistory() {
        Schedule.Shift old = shift("А", "2025-12-01", 1080), latest = shift("А", "2026-12-01", 1080);
        Map<String, Schedule.Shift> result = Schedule.refreshMonths(rows(old), rows(latest));
        assertEquals(2, result.size()); assertEquals(2, Schedule.months(result.values()).size());
    }

    @Test public void emptyCsvHasNoAuthorityToDeleteAnUnspecifiedMonth() {
        Map<String, Schedule.Shift> saved = rows(shift("А", "2026-11-01", 1080), shift("А", "2026-12-01", 1080));
        Map<String, Schedule.Shift> result = Schedule.refreshMonths(saved, new LinkedHashMap<>());
        assertEquals(saved, result); assertNotSame(saved, result);
    }

    @Test public void archivedAndCurrentLocalOverridesRemainAfterRollover() {
        Schedule.Shift old = shift("А", "2026-11-01", 1080), deleted = shift("А", "2026-11-02", 1080);
        Schedule.Shift manualOld = shift("А", "2026-11-01", 1200), added = shift("А", "2026-12-01", 1140);
        Map<String, Schedule.Shift> edits = rows(manualOld, added); edits.put(deleted.key(), null);
        Map<String, Schedule.Shift> result = Schedule.merge(Schedule.refreshMonths(rows(old, deleted), rows(shift("А", "2026-12-01", 1020))), edits);
        assertEquals(1200, result.get(old.key()).end); assertFalse(result.containsKey(deleted.key()));
        assertEquals(1140, result.get(added.key()).end);
    }
}
