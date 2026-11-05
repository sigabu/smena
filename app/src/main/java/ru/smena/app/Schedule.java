package ru.smena.app;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Time is always stored as integer minutes, never as decimal hours. */
public final class Schedule {
    private Schedule() {}

    public static final class BreakRule {
        public final int threshold, shortBreak, longBreak;
        public BreakRule(int threshold, int shortBreak, int longBreak) {
            if (threshold < 1 || threshold > 1440 || shortBreak < 0 || longBreak < 0
                    || shortBreak > 240 || longBreak > 240)
                throw new IllegalArgumentException("Проверьте правило обедов");
            this.threshold = threshold; this.shortBreak = shortBreak; this.longBreak = longBreak;
        }
        public static BreakRule inferred() { return new BreakRule(480, 30, 60); }
        public int minutes(int gross) { return Math.min(gross, gross < threshold ? shortBreak : longBreak); }
    }

    public static final class Shift {
        public final String employee, department;
        public final LocalDate date;
        public final int start, end;
        public final boolean nextDay;
        public final Integer breakMinutes;
        public Shift(String employee, String department, LocalDate date, int start, int end,
                     boolean nextDay, Integer breakMinutes) {
            if (employee == null || employee.trim().isEmpty() || employee.trim().length() > 120)
                throw new IllegalArgumentException("Укажите имя сотрудника (до 120 символов)");
            if (date == null || date.getYear() < 2000 || date.getYear() > 2100)
                throw new IllegalArgumentException("Дата должна быть между 2000 и 2100 годом");
            if (start < 0 || start >= 1440 || end < 0 || end >= 1440)
                throw new IllegalArgumentException("Время должно быть в формате ЧЧ:ММ");
            int gross = end - start + (nextDay ? 1440 : 0);
            if (gross <= 0 || gross > 1440)
                throw new IllegalArgumentException("Конец должен быть позже начала. Для ночной смены отметьте следующий день");
            if (breakMinutes != null && (breakMinutes < 0 || breakMinutes > gross))
                throw new IllegalArgumentException("Обед должен быть от 0 минут до длительности смены");
            this.employee = employee.trim(); this.department = department == null ? "" : department.trim();
            if (this.department.length() > 120) throw new IllegalArgumentException("Название отдела слишком длинное");
            this.date = date; this.start = start; this.end = end; this.nextDay = nextDay;
            this.breakMinutes = breakMinutes;
        }
        public int gross() { return end - start + (nextDay ? 1440 : 0); }
        public int lunch(BreakRule rule) { return breakMinutes == null ? rule.minutes(gross()) : breakMinutes; }
        public int net(BreakRule rule) { return gross() - lunch(rule); }
        public String key() { return key(employee, date); }
        public static String key(String employee, LocalDate date) { return date + "\t" + employee.trim(); }
        public String timeRange() { return time(start) + " – " + time(end) + (nextDay ? " +1" : ""); }
    }

    public static final class Totals {
        public int net, lunch, gross, count;
        public void add(Shift s, BreakRule rule) { net += s.net(rule); lunch += s.lunch(rule); gross += s.gross(); count++; }
    }

    public static Map<String, Shift> merge(Map<String, Shift> source, Map<String, Shift> edits) {
        Map<String, Shift> result = new LinkedHashMap<>(source);
        for (Map.Entry<String, Shift> e : edits.entrySet()) {
            if (e.getValue() == null) result.remove(e.getKey()); else result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    public static List<Shift> select(Map<String, Shift> shifts, YearMonth month, String employee) {
        List<Shift> result = new ArrayList<>();
        for (Shift s : shifts.values()) {
            if (YearMonth.from(s.date).equals(month) && (employee == null || employee.equals(s.employee))) result.add(s);
        }
        result.sort(Comparator.comparing((Shift s) -> s.date).thenComparingInt(s -> s.start).thenComparing(s -> s.employee));
        return result;
    }

    public static Totals totals(List<Shift> shifts, BreakRule rule) {
        Totals t = new Totals(); for (Shift s : shifts) t.add(s, rule); return t;
    }

    public static int parseTime(String text) {
        if (!text.trim().matches("\\d{1,2}:\\d{2}")) throw new IllegalArgumentException("Время: ЧЧ:ММ, например 09:30");
        String[] parts = text.trim().split(":");
        int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]);
        if (h > 23 || m > 59) throw new IllegalArgumentException("Некорректное время: " + text);
        return h * 60 + m;
    }

    public static LocalDate parseDate(String value) {
        String s = value.trim();
        try {
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}"))
                return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT));
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("Дата: ГГГГ-ММ-ДД или ДД.ММ.ГГГГ"); }
    }

    public static String time(int minutes) { return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60); }
    public static String hours(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60) + " ч" : (minutes / 60) + " ч " + (minutes % 60) + " мин";
    }
}
