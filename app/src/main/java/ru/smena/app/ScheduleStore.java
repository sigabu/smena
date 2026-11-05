package ru.smena.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ScheduleStore {
    public Map<String, Schedule.Shift> source = new LinkedHashMap<>();
    public Map<String, Schedule.Shift> edits = new LinkedHashMap<>();
    public Schedule.BreakRule rule = Schedule.BreakRule.inferred();
    public String sourceUrl = "", sourceName = "Пример графика";
    public String owner = "Анна Смирнова";
    public long syncedAt;
    public boolean demo = true;
    public String loadError;
    private final AtomicFile file;

    public ScheduleStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "schedule.json"));
        if (!file.getBaseFile().exists()) {
            sourceUrl = context.getString(R.string.default_source_url);
            owner = context.getString(R.string.default_employee);
            if (sourceUrl.isEmpty()) seedDemo();
            else { demo = false; sourceName = "Google Таблица"; }
            return;
        }
        try { decode(new String(file.readFully(), StandardCharsets.UTF_8)); }
        catch (Exception e) {
            // Preserve the unreadable file for recovery and prohibit accidental overwrites.
            loadError = "Не удалось прочитать сохранённый график. Данные не перезаписаны. Закройте приложение и сохраните файл schedule.json через adb перед восстановлением.";
            demo = false; source.clear(); edits.clear();
        }
    }

    public Map<String, Schedule.Shift> all() { return Schedule.merge(source, edits); }

    public void save() throws IOException {
        if (loadError != null) throw new IOException(loadError);
        FileOutputStream stream = null;
        try {
            byte[] data = encode().toString().getBytes(StandardCharsets.UTF_8);
            stream = file.startWrite(); stream.write(data); file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw new IOException("Не удалось сохранить график на телефоне", e);
        }
    }

    public JSONObject encode() throws JSONException {
        JSONObject root = new JSONObject(); root.put("version", 1); root.put("url", sourceUrl);
        root.put("name", sourceName); root.put("syncedAt", syncedAt); root.put("demo", demo); root.put("owner", owner);
        root.put("threshold", rule.threshold); root.put("shortBreak", rule.shortBreak); root.put("longBreak", rule.longBreak);
        JSONArray base = new JSONArray(); for (Schedule.Shift s : source.values()) base.put(encodeShift(s));
        root.put("source", base);
        JSONArray local = new JSONArray();
        for (Map.Entry<String, Schedule.Shift> e : edits.entrySet()) {
            JSONObject item = new JSONObject(); item.put("key", e.getKey());
            item.put("shift", e.getValue() == null ? JSONObject.NULL : encodeShift(e.getValue())); local.put(item);
        }
        root.put("edits", local); return root;
    }

    public void decode(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        if (root.getInt("version") != 1) throw new JSONException("Неизвестная версия данных");
        Map<String, Schedule.Shift> base = new LinkedHashMap<>(), local = new LinkedHashMap<>();
        JSONArray array = root.getJSONArray("source");
        for (int i = 0; i < array.length(); i++) { Schedule.Shift s = decodeShift(array.getJSONObject(i)); base.put(s.key(), s); }
        array = root.getJSONArray("edits");
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i); local.put(item.getString("key"), item.isNull("shift") ? null : decodeShift(item.getJSONObject("shift")));
        }
        Schedule.BreakRule parsedRule = new Schedule.BreakRule(root.getInt("threshold"), root.getInt("shortBreak"), root.getInt("longBreak"));
        sourceUrl = root.getString("url"); sourceName = root.getString("name"); syncedAt = root.getLong("syncedAt"); demo = root.getBoolean("demo");
        source = base; edits = local; rule = parsedRule; owner = root.optString("owner", "");
    }

    private JSONObject encodeShift(Schedule.Shift s) throws JSONException {
        JSONObject j = new JSONObject(); j.put("employee", s.employee); j.put("department", s.department); j.put("date", s.date.toString());
        j.put("start", s.start); j.put("end", s.end); j.put("nextDay", s.nextDay);
        j.put("break", s.breakMinutes == null ? JSONObject.NULL : s.breakMinutes); return j;
    }

    private Schedule.Shift decodeShift(JSONObject j) throws JSONException {
        return new Schedule.Shift(j.getString("employee"), j.getString("department"), LocalDate.parse(j.getString("date")),
                j.getInt("start"), j.getInt("end"), j.getBoolean("nextDay"), j.isNull("break") ? null : j.getInt("break"));
    }

    private void seedDemo() {
        owner = "Анна Смирнова";
        YearMonth month = YearMonth.now();
        String[] names = {"Анна Смирнова"};
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            for (int p = 0; p < names.length; p++) {
                if ((day + p) % 4 >= 2) continue;
                Schedule.Shift s = new Schedule.Shift(names[p], p == 2 ? "Склад" : "Торговый зал", month.atDay(day),
                        p == 1 ? 14 * 60 : 9 * 60 + 30, p == 1 ? 22 * 60 : 19 * 60, false, null);
                source.put(s.key(), s);
            }
        }
    }
}
