package ru.smena.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

public class CsvScheduleTest {
    private final String header = "employee,department,date,start,end,break_minutes,next_day\r\n";

    @Test public void csvRoundTripSupportsQuotedNamesAndNewlines() {
        String csv = header + "\"Анна, \"\"А\"\"\",\"Отдел\nпродаж\",2026-11-05,09:30,19:00,,false\r\n";
        Map<String, Schedule.Shift> parsed = CsvSchedule.read(csv);
        Schedule.Shift s = parsed.values().iterator().next(); assertEquals("Анна, \"А\"", s.employee);
        assertNull(s.breakMinutes); assertEquals("Отдел\nпродаж", s.department);
        Schedule.Shift copy = CsvSchedule.read(CsvSchedule.write(parsed.values())).values().iterator().next();
        assertEquals(s.employee, copy.employee); assertEquals(s.department, copy.department); assertEquals(s.gross(), copy.gross());
    }

    @Test public void bomSemicolonRussianDateAndExplicitZeroAreSupported() {
        String csv = "\uFEFFemployee;date;start;end;break_minutes\r\nАнна;05.11.2026;9:30;19:00;0\r\n";
        Schedule.Shift s = CsvSchedule.read(csv).values().iterator().next(); assertEquals(Integer.valueOf(0), s.breakMinutes);
        assertEquals(570, s.net(Schedule.BreakRule.inferred()));
    }

    @Test public void duplicateEmployeesOnSameDateAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CsvSchedule.read(header + "А,,2026-11-01,09:00,17:00,,false\nА,,2026-11-01,10:00,19:00,,false"));
    }

    @Test public void malformedImportIsRejectedEntirelyWithLineNumber() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CsvSchedule.read(header + "А,,2026-11-01,09:00,17:00,,false\nБ,,2026-11-02,wrong,19:00,,false"));
        assertTrue(e.getMessage().contains("Строка 3"));
    }

    @Test public void malformedCsvAndHtmlAndMissingHeadersAreRejected() {
        for (String invalid : new String[]{"", "<html>Login</html>", "name,date\nА,2026-11-01", header + "\"А,,2026-11-01,09:00,17:00,,false", header + "А,,2026-11-01,09:00,17:00,,maybe", header + "А,,2026-11-01,09:00,17:00"})
            assertThrows(IllegalArgumentException.class, () -> CsvSchedule.read(invalid));
    }

    @Test public void emptyHeaderOnlyImportIsExplicitlyAllowed() { assertTrue(CsvSchedule.read(header).isEmpty()); }

    @Test public void nightShiftRequiresExplicitFlag() {
        String row = "А,,2026-11-01,22:00,06:00,30,";
        assertEquals(450, CsvSchedule.read(header + row + "true").values().iterator().next().net(Schedule.BreakRule.inferred()));
        assertThrows(IllegalArgumentException.class, () -> CsvSchedule.read(header + row + "false"));
    }

    @Test public void googleLinksKeepSelectedSheet() {
        assertEquals("https://docs.google.com/spreadsheets/d/abc_123/export?format=csv&gid=42", GoogleSource.csvUrl("https://docs.google.com/spreadsheets/d/abc_123/edit?usp=sharing#gid=42"));
        assertEquals("https://docs.google.com/spreadsheets/d/e/pub123/pub?output=csv&gid=8", GoogleSource.csvUrl("https://docs.google.com/spreadsheets/d/e/pub123/pubhtml?gid=8&single=true"));
        assertEquals("https://example.com/data.csv?q=1", GoogleSource.csvUrl("https://example.com/data.csv?q=1"));
        assertThrows(IllegalArgumentException.class, () -> GoogleSource.csvUrl("http://example.com/data.csv"));
        assertThrows(IllegalArgumentException.class, () -> GoogleSource.csvUrl("https://docs.google.com/document/d/id/edit"));
        assertThrows(IllegalArgumentException.class, () -> GoogleSource.csvUrl("https://docs.google.com/spreadsheets/d/abc/edit#gid=invalid"));
    }

    @Test public void oversizedFilesAreRejected() {
        byte[] data = new byte[GoogleSource.MAX_BYTES + 1];
        assertThrows(IOException.class, () -> GoogleSource.readLimited(new ByteArrayInputStream(data)));
    }

    @Test public void spreadsheetFormulaNamesAreNeutralizedOnExport() {
        Map<String, Schedule.Shift> parsed = CsvSchedule.read(header + "=2+2,,2026-11-05,09:00,18:00,,false");
        assertTrue(CsvSchedule.write(parsed.values()).contains("\"'=2+2\""));
    }
}
