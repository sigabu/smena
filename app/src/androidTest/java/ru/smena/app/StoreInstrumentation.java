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
            // Existing 1.0/1.1 files have no history metadata. Preserve their data on upgrade.
            org.json.JSONObject legacy = loaded.encode(); legacy.remove("latestSourceMonths");
            File data = new File(folder, "schedule.json"); Files.write(data.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
            loaded = new ScheduleStore(context);
            check(loaded.loadError == null && loaded.source.size() == fresh.size()
                    && loaded.latestSourceMonths.contains(java.time.YearMonth.of(2026,11)), "Old storage is upgraded without losing November");
            loaded.edits.put(edit.key(), edit); loaded.edits.put(first.key(), null);
            int novemberMinutes = Schedule.totals(Schedule.select(loaded.all(), java.time.YearMonth.of(2026,11), "Анна"), loaded.rule).net;
            Schedule.Shift december = new Schedule.Shift("Анна", "Зал", LocalDate.of(2026,12,1), 540, 1080, false, null);
            java.util.Map<String, Schedule.Shift> nextMonth = new java.util.LinkedHashMap<>(); nextMonth.put(december.key(), december);
            loaded.applySource(nextMonth, loaded.sourceUrl, "December", false); loaded.save(); loaded = new ScheduleStore(context);
            check(loaded.source.size() == fresh.size() + 1 && loaded.isArchived(java.time.YearMonth.of(2026,11))
                    && !loaded.isArchived(java.time.YearMonth.of(2026,12)), "November is archived and December remains current after restart");
            check(Schedule.totals(Schedule.select(loaded.all(), java.time.YearMonth.of(2026,11), "Анна"), loaded.rule).net == novemberMinutes
                    && loaded.edits.size() == 2, "Archived total and manual edits survive rollover and restart");
            loaded.applySource(new java.util.LinkedHashMap<>(), loaded.sourceUrl, "Empty", false); loaded.save(); loaded = new ScheduleStore(context);
            check(loaded.source.size() == fresh.size() + 1 && loaded.isArchived(java.time.YearMonth.of(2026,11))
                    && !loaded.isArchived(java.time.YearMonth.of(2026,12)), "Empty refresh preserves stored months and metadata");
            loaded.applySource(nextMonth, loaded.sourceUrl, "Reset to December", true); loaded.save(); loaded = new ScheduleStore(context);
            check(loaded.source.size() == 1 && loaded.source.containsKey(december.key()) && loaded.edits.isEmpty()
                    && !loaded.isArchived(java.time.YearMonth.of(2026,11)), "Explicit full reset removes history and all overrides");
            String advancesName = "salary-test-" + System.nanoTime();
            SalaryAdvances advances = new SalaryAdvances(context, advancesName);
            java.time.YearMonth november = java.time.YearMonth.of(2026, 11);
            check(advances.get("Анна", november) == 0, "Existing installation starts with zero advance");
            advances.set("Анна", november, 1500050);
            check(advances.get("Анна", november) == 1500050, "Advance preserves exact kopecks");
            check(advances.get("Анна", november.plusMonths(1)) == 0, "Advance is separate for each month");
            check(advances.get("Анна", november.plusYears(1)) == 0, "Advance is separate for each year");
            check(advances.get("Борис", november) == 0, "Advance is separate for each employee");
            check(context.getSharedPreferences(advancesName, android.content.Context.MODE_PRIVATE).edit().commit(), "Advance written to disk");
            check(new SalaryAdvances(context, advancesName).get("Анна", november) == 1500050, "Advance survives reopening storage");
            advances.set("Анна", november, 0);
            check(advances.get("Анна", november) == 0, "Clearing an advance restores zero");
            context.deleteSharedPreferences(advancesName);
            Files.write(data.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
            ScheduleStore broken = new ScheduleStore(context);
            check(broken.loadError != null, "Corruption is visible");
            try { broken.save(); throw new AssertionError("Corrupted storage should not be overwritten"); }
            catch (java.io.IOException expected) { check(new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8).equals("broken"), "Unreadable data preserved"); }
            File payload = new File(folder, "update-check.bin");
            Files.write(payload.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            UpdateManifest update = new UpdateManifest(4, "1.3",
                    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", 5, "a".repeat(40));
            AppUpdate.verifyIntegrity(payload, update); check(true, "Update size and SHA-256 accepted for matching file");
            Files.write(payload.toPath(), "hallo".getBytes(StandardCharsets.UTF_8));
            try { AppUpdate.verifyIntegrity(payload, update); throw new AssertionError("Corrupt update accepted"); }
            catch (java.io.IOException expected) { check(true, "Same-size corrupt update rejected"); }
            Files.write(payload.toPath(), "hell".getBytes(StandardCharsets.UTF_8));
            try { AppUpdate.verifyIntegrity(payload, update); throw new AssertionError("Truncated update accepted"); }
            catch (java.io.IOException expected) { check(true, "Truncated update rejected"); }
            try { AppUpdate.verifyApk(context, payload, update); throw new AssertionError("Non-APK update accepted"); }
            catch (java.io.IOException expected) { check(true, "Non-APK update rejected by package validation"); }
            try {
                AppUpdate.verifyApk(context, new File(context.getApplicationInfo().sourceDir),
                        new UpdateManifest((int) AppUpdate.installedCode(context), AppUpdate.installedName(context), update.sha256, 5, update.commit));
                throw new AssertionError("Current-version APK accepted as update");
            } catch (java.io.IOException expected) { check(true, "Current-version APK rejected as update"); }
            android.net.Uri updateUri = android.net.Uri.parse("content://" + context.getPackageName() + ".updates/smena-update.apk");
            check(UpdateProvider.MIME.equals(context.getContentResolver().getType(updateUri)), "Installer gets APK MIME type");
            check(context.getContentResolver().getType(android.net.Uri.parse("content://" + context.getPackageName() + ".updates/schedule.json")) == null,
                    "Update provider does not expose schedule storage");
            try {
                android.os.ParcelFileDescriptor writable = context.getContentResolver().openFileDescriptor(updateUri, "w");
                if (writable != null) writable.close();
                throw new AssertionError("Update provider allowed writing");
            } catch (java.io.FileNotFoundException expected) { check(true, "Update provider rejects writable file handles"); }
            result.putString("stream", "\nOK (" + checks + " Android storage and update checks)\n"); finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(e)); finish(0, result);
        }
    }
    private void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
}
