package ru.smena.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CsvSchedule {
    private CsvSchedule() {}
    public static Map<String, Schedule.Shift> read(String input) {
        String text = input.replace("\uFEFF", "");
        if (text.trim().startsWith("<")) throw new IllegalArgumentException("Вместо CSV получена веб-страница. Проверьте доступ к таблице");
        List<List<String>> rows = parse(text);
        if (rows.isEmpty()) throw new IllegalArgumentException("Файл пуст");
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            String name = rows.get(0).get(i).trim().toLowerCase(Locale.ROOT);
            if (columns.put(name, i) != null) throw new IllegalArgumentException("Повторяется столбец: " + name);
        }
        for (String required : new String[]{"employee", "date", "start", "end"})
            if (!columns.containsKey(required)) throw new IllegalArgumentException("Нет столбца " + required + ". Используйте шаблон из инструкции");
        Map<String, Schedule.Shift> result = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(s -> s.trim().isEmpty())) continue;
            try {
                if (row.size() != rows.get(0).size()) throw new IllegalArgumentException("Количество полей не совпадает с заголовком");
                String lunch = cell(row, columns, "break_minutes");
                String next = cell(row, columns, "next_day").toLowerCase(Locale.ROOT);
                if (!next.isEmpty() && !next.equals("true") && !next.equals("false") && !next.equals("1") && !next.equals("0"))
                    throw new IllegalArgumentException("next_day: true/false или 1/0");
                Schedule.Shift s = new Schedule.Shift(cell(row, columns, "employee"), cell(row, columns, "department"),
                        Schedule.parseDate(cell(row, columns, "date")), Schedule.parseTime(cell(row, columns, "start")),
                        Schedule.parseTime(cell(row, columns, "end")), next.equals("true") || next.equals("1"),
                        lunch.isEmpty() ? null : Integer.valueOf(lunch));
                if (result.put(s.key(), s) != null) throw new IllegalArgumentException("Две смены одного сотрудника на одну дату");
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Строка " + (i + 1) + ": " + e.getMessage()); }
            if (result.size() > 10000) throw new IllegalArgumentException("Максимум 10 000 смен в файле");
        }
        return result;
    }

    private static String cell(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name); return index == null ? "" : row.get(index).trim();
    }

    public static List<List<String>> parse(String input) {
        // Detect delimiter only outside quotes in the header.
        boolean quoted = false; int commas = 0, semis = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') quoted = !quoted;
            if (!quoted && (c == '\r' || c == '\n')) break;
            if (!quoted && c == ',') commas++;
            if (!quoted && c == ';') semis++;
        }
        char delimiter = semis > commas ? ';' : ',';
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder(); boolean inQuotes = false, closed = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { inQuotes = false; closed = true; }
                } else field.append(c);
            } else if (c == delimiter || c == '\n' || c == '\r') {
                row.add(field.toString()); field.setLength(0); closed = false;
                if (c != delimiter) {
                    rows.add(row); row = new ArrayList<>();
                    if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                }
            } else if (c == '"' && field.length() == 0 && !closed) inQuotes = true;
            else if (closed || c == '"') throw new IllegalArgumentException("Некорректные кавычки в CSV");
            else field.append(c);
        }
        if (inQuotes) throw new IllegalArgumentException("Незакрытые кавычки в CSV");
        if (field.length() > 0 || !row.isEmpty() || closed) { row.add(field.toString()); rows.add(row); }
        return rows;
    }

    public static String write(Iterable<Schedule.Shift> shifts) {
        StringBuilder out = new StringBuilder("employee,department,date,start,end,break_minutes,next_day\r\n");
        for (Schedule.Shift s : shifts) {
            out.append(quote(s.employee)).append(',').append(quote(s.department)).append(',')
                    .append(s.date).append(',').append(Schedule.time(s.start)).append(',').append(Schedule.time(s.end)).append(',')
                    .append(s.breakMinutes == null ? "" : s.breakMinutes).append(',').append(s.nextDay).append("\r\n");
        }
        return out.toString();
    }
    private static String quote(String value) {
        // Prevent spreadsheet formula execution if the exported CSV is opened in Sheets/Excel.
        if (value.matches("(?s)^[=+@\\-\\t\\r].*")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
