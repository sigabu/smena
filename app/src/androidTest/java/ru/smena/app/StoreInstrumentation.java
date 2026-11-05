package ru.smena.app;

import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;

/** Runs on Android to exercise real JSONObject and AtomicFile rather than mocks. */
public class StoreInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            File folder = new File(getTargetContext().getCacheDir(), "store-test-" + System.nanoTime());
            if (!folder.mkdirs()) throw new AssertionError("Test directory unavailable");
            ContextWrapper context = new ContextWrapper(getTargetContext()) {
                @Override public File getFilesDir() { return folder; }
            };
            ScheduleStore store = new ScheduleStore(context);
            boolean configured = !context.getString(R.string.default_source_url).isEmpty();
            check(configured ? !store.demo && store.all().isEmpty() && store.owner.equals(context.getString(R.string.default_employee))
                    : store.demo && !store.all().isEmpty(), "First launch matches build configuration");
            store.source.clear(); store.edits.clear(); store.demo = false; store.owner = "Анна";
            Schedule.Shift first = new Schedule.Shift("Анна", "Зал", LocalDate.of(2026,11,1), 540, 1080, false, null);
            Schedule.Shift second = new Schedule.Shift("Анна", "Зал", LocalDate.of(2026,11,2), 540, 1080, false, 0);
            store.source.put(first.key(), first); store.source.put(second.key(), second); store.edits.put(first.key(), null);
            Schedule.Shift edit = new Schedule.Shift("Анна", "Зал", second.date, 600, 1080, false, 45);
            store.edits.put(edit.key(), edit); store.rule = new Schedule.BreakRule(450, 20, 50);
            store.sourceUrl = "https://example.com/schedule.csv"; store.syncedAt = 123456; store.save();
            ScheduleStore loaded = new ScheduleStore(context);
            check(loaded.loadError == null && !loaded.demo && loaded.owner.equals("Анна"), "Profile survives restart");
            check(loaded.all().size() == 1 && !loaded.all().containsKey(first.key()), "Deletion survives restart");
            check(loaded.all().get(edit.key()).net(loaded.rule) == 435, "Edited shift and explicit lunch survive restart");
            check(loaded.rule.threshold == 450 && loaded.rule.shortBreak == 20 && loaded.rule.longBreak == 50, "Rule survives restart");
            check(loaded.sourceUrl.equals(store.sourceUrl) && loaded.syncedAt == 123456, "Source survives restart");
            loaded.source.put(first.key(), first); loaded.save();
            check(!new ScheduleStore(context).all().containsKey(first.key()), "Refresh cannot resurrect a locally deleted shift");
            loaded.edits.remove(first.key()); loaded.save();
            check(new ScheduleStore(context).all().size() == 2, "Reverting an edit restores source");
            Schedule.Shift added = new Schedule.Shift("Анна", "Зал", LocalDate.of(2026,11,3), 600, 1020, false, 20);
            Schedule.Shift ordinary = new Schedule.Shift("Анна", "Зал", LocalDate.of(2026,11,4), 540, 1080, false, null);
            Schedule.Shift removedUpstream = new Schedule.Shift("Анна", "Зал", LocalDate.of(2026,11,5), 540, 1080, false, null);
            loaded.edits.put(first.key(), null); loaded.edits.put(added.key(), added);
            loaded.source.put(ordinary.key(), ordinary); loaded.source.put(removedUpstream.key(), removedUpstream);
            java.util.Map<String, Schedule.Shift> fresh = new java.util.LinkedHashMap<>();
            fresh.put(first.key(), first); fresh.put(second.key(), second);
            fresh.put(added.key(), new Schedule.Shift("Анна", "Зал", added.date, 480, 1200, false, null));
            fresh.put(ordinary.key(), new Schedule.Shift("Анна", "Зал", ordinary.date, 600, 1200, false, null));
            Schedule.Shift upstreamAdded = new Schedule.Shift("Анна", "Зал", LocalDate.of(2026,11,6), 600, 1200, false, null);
            fresh.put(upstreamAdded.key(), upstreamAdded);
            loaded.applySource(fresh, loaded.sourceUrl, "Updated", false); loaded.save();
            loaded = new ScheduleStore(context);
            check(!loaded.all().containsKey(first.key()) && loaded.all().get(second.key()).breakMinutes == 45, "Refresh preserves deletions and edited shifts");
            check(loaded.all().get(added.key()).start == 600 && loaded.all().get(added.key()).breakMinutes == 20, "Local addition wins an upstream collision");
            check(loaded.all().get(ordinary.key()).end == 1200 && !loaded.all().containsKey(removedUpstream.key()) && loaded.all().containsKey(upstreamAdded.key()), "Untouched shifts follow all upstream changes");
            loaded.applySource(fresh, loaded.sourceUrl, "Reset", true); loaded.save(); loaded = new ScheduleStore(context);
            check(loaded.edits.isEmpty() && loaded.all().keySet().equals(fresh.keySet()), "Full reset restores exactly the fresh source");
            check(loaded.all().get(added.key()).start == 480 && loaded.all().get(second.key()).breakMinutes == 0, "Reset removes all local shift values");
            check(loaded.rule.threshold == 480 && loaded.rule.shortBreak == 30 && loaded.rule.longBreak == 60, "Full reset restores default automatic lunches");
            File data = new File(folder, "schedule.json"); Files.write(data.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
            ScheduleStore broken = new ScheduleStore(context);
            check(broken.loadError != null, "Corruption is visible");
            try { broken.save(); throw new AssertionError("Corrupted storage should not be overwritten"); }
            catch (java.io.IOException expected) { check(new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8).equals("broken"), "Unreadable data preserved"); }
            result.putString("stream", "\nOK (" + checks + " Android storage checks)\n"); finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(e)); finish(0, result);
        }
    }
    private void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
}
