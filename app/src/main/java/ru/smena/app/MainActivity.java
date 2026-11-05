package ru.smena.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int LIME = 0xFFD6ED91, WHITE = Color.WHITE, GREEN = 0xFF173E35;
    private int BG, INK, MUTED, CARD, NOTE, SHIFT_BG, SHIFT_INK, ORANGE;
    private boolean dark;
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final int OPEN_CSV = 10, EXPORT_CSV = 11, EXPORT_TEMPLATE = 12;
    private ScheduleStore store;
    private YearMonth month = YearMonth.now();
    private LocalDate day = LocalDate.now();
    private String employee;
    private int screen;
    private LinearLayout root, content;
    private ScrollView bodyScroll;
    private LinearLayout dayDetails;
    private final Map<LocalDate, LinearLayout> calendarCells = new LinkedHashMap<>();
    private List<Schedule.Shift> calendarShifts;
    private PullRefreshLayout refreshLayout;
    private Button sourceButton, fileButton, resetButton;
    private int renderedScreen = -1;
    private int restoredScroll;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy;
    private boolean resetting;
    private String pendingExport;
    private String sourceDraft;

    @Override public void onCreate(Bundle state) {
        dark = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("dark", false);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        BG = dark ? 0xFF111C18 : 0xFFF5F5EF;
        INK = dark ? 0xFFE6EEE7 : GREEN;
        MUTED = dark ? 0xFFACBEB1 : 0xFF66736C;
        CARD = dark ? 0xFF203229 : WHITE;
        NOTE = dark ? 0xFF2B3E2C : 0xFFE7EBD9;
        SHIFT_BG = dark ? 0xFF354B26 : LIME;
        SHIFT_INK = dark ? 0xFFE2F2B5 : GREEN;
        ORANGE = dark ? 0xFFF2BF80 : 0xFF89551D;
        store = new ScheduleStore(this);
        employee = store.owner;
        sourceDraft = store.sourceUrl;
        if (state != null) {
            month = YearMonth.parse(state.getString("month", month.toString()));
            day = LocalDate.parse(state.getString("day", day.toString()));
            employee = state.getString("employee"); screen = state.getInt("screen");
            pendingExport = state.getString("export");
            sourceDraft = state.getString("sourceDraft", store.sourceUrl);
            restoredScroll = state.getInt("scroll");
        }
        render();
        if (store.loadError != null) error(store.loadError);
        else if (state == null && !store.sourceUrl.isEmpty() && store.syncedAt == 0 && store.source.isEmpty() && store.edits.isEmpty()) fetch(store.sourceUrl, true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("month", month.toString()); state.putString("day", day.toString());
        state.putString("employee", employee); state.putInt("screen", screen); state.putString("export", pendingExport);
        state.putString("sourceDraft", sourceDraft);
        state.putInt("scroll", bodyScroll == null ? 0 : bodyScroll.getScrollY());
    }

    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }

    private void render() {
        int scrollPosition = renderedScreen == screen && bodyScroll != null ? bodyScroll.getScrollY() : restoredScroll;
        restoredScroll = 0; renderedScreen = screen;
        sourceButton = null; fileButton = null; resetButton = null; refreshLayout = null;
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout heading = row(); heading.setPadding(dp(20), dp(12), dp(16), dp(6));
        TextView logo = text("◷  смена", 25, INK, true); heading.addView(logo, weight());
        Button settings = button("Обеды", false, this::breakSettings); settings.setContentDescription("Настроить расчёт обедов"); heading.addView(settings);
        root.addView(heading);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        bodyScroll = scroll;
        content = column(); content.setPadding(dp(20), dp(10), dp(20), dp(24)); scroll.addView(content);
        if (screen == 0) {
            refreshLayout = new PullRefreshLayout(this, scroll, INK, CARD);
            refreshLayout.setOnRefresh(this::refreshCurrentSource);
            refreshLayout.setRefreshing(busy);
            root.addView(refreshLayout, new LinearLayout.LayoutParams(-1, 0, 1));
        } else root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (screen == 0) calendarScreen(); else sourceScreen();
        LinearLayout nav = row(); nav.setPadding(dp(8), dp(5), dp(8), dp(5)); nav.setBackgroundColor(CARD);
        String[] labels = {"Мой график", "Настройки"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i; Button b = button(labels[i], screen == i, () -> { screen = tab; render(); });
            b.setTextSize(13); nav.addView(b, weight());
        }
        root.addView(nav);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!dark && android.os.Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                scroll.scrollTo(0, scrollPosition); return true;
            }
        });
    }

    private void monthHeader() {
        LinearLayout line = row();
        Button prev = button("‹", false, () -> moveMonth(-1)); prev.setContentDescription("Предыдущий месяц");
        Button next = button("›", false, () -> moveMonth(1)); next.setContentDescription("Следующий месяц");
        line.addView(prev, new LinearLayout.LayoutParams(dp(48), dp(48)));
        String title = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU));
        TextView label = text(title.substring(0, 1).toUpperCase(RU) + title.substring(1), 22, INK, true);
        label.setGravity(Gravity.CENTER); line.addView(label, weight());
        line.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48))); content.addView(line); gap(content, 12);
    }

    private void moveMonth(int delta) {
        YearMonth target = month.plusMonths(delta);
        if (target.getYear() < 2000 || target.getYear() > 2100) return;
        month = target; day = month.atDay(Math.min(day.getDayOfMonth(), month.lengthOfMonth())); render();
    }

    private List<String> employees() {
        TreeSet<String> names = new TreeSet<>(); for (Schedule.Shift s : store.all().values()) names.add(s.employee);
        return new ArrayList<>(names);
    }

    private void employeeSelector() {
        employee = store.owner;
        TextView label = text(employee.isEmpty() ? "Личный график · добавьте первую смену" : employee, 16, MUTED, false);
        content.addView(label); gap(content, 14);
    }

    private void calendarScreen() {
        calendarCells.clear();
        monthHeader(); employeeSelector();
        List<Schedule.Shift> shifts = Schedule.select(store.all(), month, employee);
        calendarShifts = shifts;
        Schedule.Totals t = Schedule.totals(shifts, store.rule);
        LinearLayout summary = column(); summary.setPadding(dp(20), dp(18), dp(20), dp(18)); summary.setBackground(background(GREEN, 20));
        summary.addView(text("МОИ РАБОЧИЕ ЧАСЫ ЗА МЕСЯЦ", 11, LIME, true));
        summary.addView(text(Schedule.hours(t.net), 34, WHITE, true)); gap(summary, 6);
        summary.addView(text(t.count + " смен  ·  обеды " + Schedule.hours(t.lunch), 14, 0xFFE1EAD8, false));
        summary.addView(text("До вычета обедов: " + Schedule.hours(t.gross), 12, 0xFFBBCDC4, false)); content.addView(summary);
        gap(content, 12);
        if (store.demo) note("Пример графика · подключите свой в настройках", () -> { screen = 1; render(); });
        else if (store.syncedAt == 0 && !store.sourceUrl.isEmpty()) note(busy ? "Загружаем ваш график…" : "График ещё не загружен · откройте настройки", () -> { screen = 1; render(); });
        else if (!store.edits.isEmpty()) note("Локальных правок: " + store.edits.size() + " · сохранены на телефоне", () -> { screen = 1; render(); });
        else content.addView(text("Потяните вниз в начале экрана, чтобы обновить", 11, MUTED, false));
        if (store.isArchived(month)) {
            gap(content, 8);
            content.addView(text("Архив · месяц сохранён на телефоне, в текущей таблице его нет", 12, MUTED, false));
        }
        gap(content, 10);
        LinearLayout weekdays = row();
        for (String label : new String[]{"ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"}) {
            TextView v = text(label, 11, MUTED, true); v.setGravity(Gravity.CENTER); weekdays.addView(v, weight());
        }
        content.addView(weekdays); gap(content, 4);
        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        int cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7;
        LinearLayout week = null;
        for (int i = 0; i < cells; i++) {
            if (i % 7 == 0) { week = row(); content.addView(week); }
            int number = i - offset + 1;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1); params.setMargins(dp(2), dp(2), dp(2), dp(2));
            if (number < 1 || number > month.lengthOfMonth()) { week.addView(new View(this), params); continue; }
            LocalDate date = month.atDay(number); int net = 0, count = 0; boolean edited = false;
            for (Schedule.Shift s : shifts) if (s.date.equals(date)) { net += s.net(store.rule); count++; edited |= store.edits.containsKey(s.key()); }
            boolean selected = date.equals(day);
            LinearLayout cell = column(); cell.setGravity(Gravity.CENTER);
            cell.setTag(count > 0);
            TextView n = text(String.valueOf(number) + (date.equals(LocalDate.now()) ? "·" : ""), 16, INK, true); n.setGravity(Gravity.CENTER); cell.addView(n);
            TextView small = text(count == 0 ? "—" : compactHours(net) + (edited ? "*" : ""), 9, INK, false); small.setGravity(Gravity.CENTER); cell.addView(small);
            paintDay(cell, selected);
            cell.setContentDescription(date + ", " + count + " смен, " + Schedule.hours(net));
            calendarCells.put(date, cell);
            cell.setOnClickListener(v -> selectDay(date)); week.addView(cell, params);
        }
        gap(content, 16);
        dayDetails = column(); dayDetails.setMinimumHeight(dp(190)); content.addView(dayDetails);
        renderDayDetails();
        gap(content, 10); content.addView(text("Часы указаны без обеда. * — локальная правка.\nНочная смена целиком относится к дате начала.", 11, MUTED, false));
    }

    private void selectDay(LocalDate selected) {
        day = selected;
        for (Map.Entry<LocalDate, LinearLayout> entry : calendarCells.entrySet()) paintDay(entry.getValue(), entry.getKey().equals(day));
        renderDayDetails();
    }

    private void paintDay(LinearLayout cell, boolean selected) {
        boolean hasShift = Boolean.TRUE.equals(cell.getTag());
        cell.setSelected(selected);
        cell.setBackground(background(selected ? (dark ? LIME : GREEN) : hasShift ? SHIFT_BG : CARD, 12));
        ((TextView) cell.getChildAt(0)).setTextColor(selected ? (dark ? GREEN : WHITE) : hasShift ? SHIFT_INK : INK);
        ((TextView) cell.getChildAt(1)).setTextColor(selected ? (dark ? GREEN : LIME) : hasShift ? SHIFT_INK : MUTED);
    }

    private void renderDayDetails() {
        dayDetails.removeAllViews();
        List<Schedule.Shift> shifts = calendarShifts;
        LinearLayout title = row(); title.addView(text(day.format(DateTimeFormatter.ofPattern("d MMMM, EEE", RU)), 19, INK, true), weight());
        boolean hasShift = shifts.stream().anyMatch(s -> s.date.equals(day));
        title.addView(button(hasShift ? "Изменить" : "+ Смена", true, () -> editShift(shifts.stream().filter(s -> s.date.equals(day)).findFirst().orElse(null)))); dayDetails.addView(title); gap(dayDetails, 8);
        int count = 0;
        for (Schedule.Shift s : shifts) if (s.date.equals(day)) { shiftCard(s); count++; }
        if (count == 0) {
            boolean awaiting = store.syncedAt == 0 && !store.sourceUrl.isEmpty();
            LinearLayout empty = card(); empty.addView(text(awaiting ? "Ожидаем график" : "Свободный день", 18, INK, true));
            empty.addView(text(awaiting ? "Для первой загрузки нужен интернет." : "Добавьте смену или выберите другую дату.", 14, MUTED, false)); dayDetails.addView(empty);
        }
    }

    private String compactHours(int minutes) {
        return minutes % 60 == 0 ? minutes / 60 + " ч" : String.format(RU, "%.1f ч", minutes / 60.0);
    }

    private void shiftCard(Schedule.Shift s) {
        LinearLayout card = card(); LinearLayout top = row();
        top.addView(text(s.employee, 16, INK, true), weight()); top.addView(text(Schedule.hours(s.net(store.rule)), 14, INK, true)); card.addView(top);
        gap(card, 7); card.addView(text(s.timeRange() + "  ·  обед " + s.lunch(store.rule) + " мин", 15, INK, false));
        String detail = s.department;
        if (store.edits.containsKey(s.key())) detail += (detail.isEmpty() ? "" : " · ") + "Изменено на телефоне";
        if (!detail.isEmpty()) { gap(card, 5); card.addView(text(detail, 12, MUTED, false)); }
        card.setContentDescription(s.employee + ", " + s.timeRange() + ". Редактировать смену");
        card.setOnClickListener(v -> editShift(s)); dayDetails.addView(card); gap(dayDetails, 8);
    }

    private void chooseOwner() {
        List<String> names = employees();
        if (names.isEmpty()) { toast("Сначала добавьте смену или загрузите таблицу"); return; }
        new AlertDialog.Builder(this).setTitle("Кто вы в таблице?")
                .setSingleChoiceItems(names.toArray(new String[0]), names.indexOf(store.owner), (d, which) -> {
                    if (mutate(() -> store.owner = names.get(which))) { employee = store.owner; d.dismiss(); render(); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void sourceScreen() {
        content.addView(text("Настройки", 29, INK, true)); gap(content, 16);
        Switch theme = new Switch(this); theme.setText("Тёмная тема"); theme.setTextSize(17); theme.setTextColor(INK);
        theme.setPadding(0, dp(12), 0, dp(12)); theme.setChecked(dark); theme.setContentDescription("Тёмная тема"); content.addView(theme);
        theme.setOnCheckedChangeListener((view, checked) -> {
            getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("dark", checked).apply(); recreate();
        });
        gap(content, 12); content.addView(text("Источник графика", 20, INK, true)); gap(content, 8);
        content.addView(text("Google Таблицы или CSV-файл. После загрузки график доступен без интернета.", 15, MUTED, false)); gap(content, 20);
        LinearLayout status = card(); status.addView(text(store.sourceName, 17, INK, true)); gap(status, 5);
        status.addView(text(store.demo ? "Демонстрационные данные, имена вымышлены" : "Сохранено смен: " + store.source.size(), 13, MUTED, false));
        if (!store.demo) status.addView(text("Месяцев на телефоне: " + Schedule.months(store.all().values()).size(), 13, MUTED, false));
        if (store.syncedAt != 0) status.addView(text("Загружен " + new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", RU).format(new java.util.Date(store.syncedAt)), 12, MUTED, false));
        status.addView(text("Локальных правок: " + store.edits.size(), 13, MUTED, false)); content.addView(status); gap(content, 12);
        content.addView(button("Мой сотрудник: " + (store.owner.isEmpty() ? "не выбран" : store.owner), false, this::chooseOwner)); gap(content, 12);
        EditText url = input(content, "Ссылка на Google-таблицу или CSV", sourceDraft, "https://docs.google.com/spreadsheets/d/…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { sourceDraft = s.toString(); }
            public void afterTextChanged(Editable s) {}
        });
        Button sync = button(busy ? "Загрузка…" : "Загрузить по ссылке", true, () -> fetch(url.getText().toString())); sourceButton = sync; sync.setEnabled(!busy); content.addView(sync);
        gap(content, 8); Button file = button("Открыть CSV с телефона", false, () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
            launchDocument(intent, OPEN_CSV);
        }); fileButton = file; file.setEnabled(!busy); content.addView(file); gap(content, 12);
        content.addView(text("Для загрузки по ссылке таблица должна быть доступна на чтение без входа. Для закрытой таблицы скачайте CSV и откройте его здесь.", 13, MUTED, false)); gap(content, 18);
        content.addView(text("Формат таблицы", 20, INK, true)); gap(content, 6);
        content.addView(text("Одна строка — одна смена. Столбцы:\nemployee, department, date, start, end, break_minutes, next_day\n\nПустой break_minutes — обед по правилу. 0 — без обеда. Для ночной смены next_day = true.", 13, MUTED, false)); gap(content, 10);
        content.addView(button("Скачать шаблон CSV", false, () -> export(EXPORT_TEMPLATE)));
        content.addView(button("Экспорт моего графика с правками", false, () -> export(EXPORT_CSV)));
        gap(content, 18); content.addView(text("Правки на телефоне", 20, INK, true)); gap(content, 6);
        content.addView(text("Ручные правки сохраняются только здесь и имеют приоритет при обновлении. Месяцы, которых больше нет в таблице, остаются в архиве. Переключайте месяцы стрелками в календаре.", 13, MUTED, false));
        if (!store.edits.isEmpty()) {
            gap(content, 10); content.addView(button("Посмотреть и отменить правки", false, this::showEdits));
        }
        gap(content, 18);
        content.addView(text("Полный сброс графика", 20, INK, true)); gap(content, 6);
        content.addView(text("Загрузить актуальную таблицу и удалить весь архив и все локальные правки. Месяцы, которых нет в таблице, исчезнут. Правило обедов вернётся к исходному. Тема и выбранный сотрудник сохранятся.", 13, MUTED, false));
        resetButton = button("Сбросить график к таблице", false, this::confirmReset); resetButton.setEnabled(!busy); content.addView(resetButton);
        if (store.demo) {
            gap(content, 14); content.addView(button("Начать с пустого графика", false, () -> new AlertDialog.Builder(this)
                    .setTitle("Создать свой график?").setMessage("Пример и его локальные правки будут удалены с телефона.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Создать", (d, w) -> mutate(() -> {
                        store.source.clear(); store.edits.clear(); store.latestSourceMonths.clear(); store.demo = false; store.sourceUrl = ""; store.sourceName = "Мой график"; store.syncedAt = 0; store.owner = "";
                    })).show()));
        }
    }

    private void editShift(Schedule.Shift original) {
        if (resetting) { toast("Дождитесь завершения сброса"); return; }
        ScrollView scroll = new ScrollView(this); LinearLayout form = column(); form.setPadding(dp(22), dp(8), dp(22), dp(8)); scroll.addView(form);
        form.addView(text("Сотрудник", 12, MUTED, true)); AutoCompleteTextView who = new AutoCompleteTextView(this);
        who.setText(original == null ? (employee == null ? "" : employee) : original.employee);
        who.setHint("Имя и фамилия"); who.setTextSize(16); who.setSingleLine(true); who.setThreshold(0);
        who.setEnabled(store.owner.isEmpty());
        who.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, employees())); form.addView(who, new LinearLayout.LayoutParams(-1, dp(52)));
        EditText dept = input(form, "Отдел (необязательно)", original == null ? "" : original.department, "Торговый зал", InputType.TYPE_CLASS_TEXT);
        EditText date = input(form, "Дата начала", (original == null ? day : original.date).toString(), "2026-11-05", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        LinearLayout times = row(); LinearLayout left = column(), right = column(); times.addView(left, weight()); times.addView(right, weight());
        EditText start = input(left, "Начало", original == null ? "09:30" : Schedule.time(original.start), "09:30", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        EditText end = input(right, "Конец", original == null ? "19:00" : Schedule.time(original.end), "19:00", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME); form.addView(times);
        CheckBox next = new CheckBox(this); next.setText("Конец на следующий день"); next.setTextSize(14); next.setChecked(original != null && original.nextDay); form.addView(next);
        EditText lunch = input(form, "Обед, минут · пусто = автоматически", original == null || original.breakMinutes == null ? "" : original.breakMinutes.toString(), "По правилу", InputType.TYPE_CLASS_NUMBER);
        TextView preview = text("", 14, INK, true); form.addView(preview); gap(form, 8);
        TextView validation = text("", 13, ORANGE, false); form.addView(validation);
        Runnable update = () -> {
            try {
                Schedule.Shift s = formShift(who, dept, date, start, end, next, lunch, true);
                preview.setText(getString(R.string.shift_preview, Schedule.hours(s.net(store.rule)), s.lunch(store.rule)));
            } catch (IllegalArgumentException e) { preview.setText("Укажите корректное время для расчёта"); }
        };
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { update.run(); }
            public void afterTextChanged(Editable s) {}
        };
        for (EditText field : new EditText[]{start, end, lunch, date}) field.addTextChangedListener(watcher);
        next.setOnCheckedChangeListener((v, checked) -> update.run()); update.run();
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(original == null ? "Новая смена" : "Изменить смену")
                .setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null);
        if (original != null) builder.setNeutralButton("Удалить", null);
        AlertDialog dialog = builder.create(); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Schedule.Shift s = formShift(who, dept, date, start, end, next, lunch, false);
                if ((original == null || !original.key().equals(s.key())) && store.all().containsKey(s.key()))
                    throw new IllegalArgumentException("На эту дату у сотрудника уже есть смена. Откройте её для редактирования");
                if (mutate(() -> {
                    if (original != null && !original.key().equals(s.key())) removeShift(original);
                    store.edits.put(s.key(), s);
                    store.owner = s.employee;
                })) {
                    day = s.date; month = YearMonth.from(day); employee = s.employee; screen = 0; dialog.dismiss(); render();
                }
            } catch (IllegalArgumentException e) { validation.setText(e.getMessage()); }
        });
        if (original != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Удалить смену?").setMessage(original.employee + "\n" + original.date + " · " + original.timeRange())
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> {
                    if (mutate(() -> removeShift(original))) dialog.dismiss();
                }).show());
    }

    private Schedule.Shift formShift(EditText who, EditText dept, EditText date, EditText start, EditText end, CheckBox next, EditText lunch, boolean preview) {
        String value = lunch.getText().toString().trim();
        try {
            return new Schedule.Shift(preview ? "Предпросмотр" : who.getText().toString(), dept.getText().toString(), Schedule.parseDate(date.getText().toString()),
                    Schedule.parseTime(start.getText().toString()), Schedule.parseTime(end.getText().toString()), next.isChecked(), value.isEmpty() ? null : Integer.valueOf(value));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Обед указывается целым числом минут"); }
    }

    private void removeShift(Schedule.Shift shift) {
        if (store.source.containsKey(shift.key())) store.edits.put(shift.key(), null); else store.edits.remove(shift.key());
    }

    private void breakSettings() {
        if (resetting) { toast("Дождитесь завершения сброса"); return; }
        LinearLayout form = column(); form.setPadding(dp(22), dp(8), dp(22), dp(8));
        form.addView(text("По фото: менее 8 часов — обед 30 минут, от 8 часов — 60. Правило согласуется с итогами; точную границу уточните у составителя графика.", 14, MUTED, false)); gap(form, 12);
        EditText threshold = input(form, "Порог длительности смены, минут", String.valueOf(store.rule.threshold), "480", InputType.TYPE_CLASS_NUMBER);
        EditText shortBreak = input(form, "Обед для смен короче порога, минут", String.valueOf(store.rule.shortBreak), "30", InputType.TYPE_CLASS_NUMBER);
        EditText longBreak = input(form, "Обед от порога включительно, минут", String.valueOf(store.rule.longBreak), "60", InputType.TYPE_CLASS_NUMBER);
        form.addView(text("Правило применяется ко всем сменам с автоматическим обедом. Явно заданный обед не меняется. Обед ограничен длительностью смены.", 12, MUTED, false));
        TextView validation = text("", 13, ORANGE, false); form.addView(validation);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Расчёт обедов").setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Применить", null).create(); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Schedule.BreakRule rule = new Schedule.BreakRule(Integer.parseInt(threshold.getText().toString()), Integer.parseInt(shortBreak.getText().toString()), Integer.parseInt(longBreak.getText().toString()));
                if (mutate(() -> store.rule = rule)) dialog.dismiss();
            } catch (IllegalArgumentException e) { validation.setText(R.string.invalid_break_rule); }
        });
    }

    private void fetch(String url) { fetch(url, false); }

    private void fetch(String url, boolean initial) { fetch(url, initial, false); }

    private void setBusy(boolean value) {
        busy = value;
        if (refreshLayout != null) refreshLayout.setRefreshing(value);
        if (sourceButton != null) { sourceButton.setEnabled(!value); sourceButton.setText(value ? "Загрузка…" : "Загрузить по ссылке"); }
        if (fileButton != null) fileButton.setEnabled(!value);
        if (resetButton != null) resetButton.setEnabled(!value);
    }

    private void refreshCurrentSource() {
        if (busy) return;
        if (store.sourceUrl.isEmpty()) {
            if (refreshLayout != null) refreshLayout.setRefreshing(false);
            toast("Укажите ссылку на таблицу в настройках"); return;
        }
        fetch(store.sourceUrl);
    }

    private void confirmReset() {
        if (busy) return;
        if (store.sourceUrl.isEmpty()) { error("Для сброса подключите Google-таблицу в настройках"); return; }
        new AlertDialog.Builder(this).setTitle("Сбросить график к таблице?")
                .setMessage("Будут удалены весь архив и все локальные правки во всех месяцах. Останутся только смены из текущей таблицы. Правило обедов вернётся к исходному. Сначала загрузим и проверим таблицу; при ошибке ваши данные сохранятся.")
                .setNegativeButton("Отмена", null).setPositiveButton("Сбросить", (dialog, which) -> fetch(store.sourceUrl, false, true)).show();
    }

    private void fetch(String url, boolean initial, boolean reset) {
        if (busy) return;
        final String candidate = url.trim();
        try { GoogleSource.csvUrl(candidate); } catch (IllegalArgumentException e) { error(e.getMessage()); return; }
        final boolean sameSource = !store.sourceUrl.isEmpty() && GoogleSource.csvUrl(candidate).equals(GoogleSource.csvUrl(store.sourceUrl));
        resetting = reset; setBusy(true);
        io.execute(() -> {
            try {
                Map<String, Schedule.Shift> parsed = CsvSchedule.read(GoogleSource.fetch(candidate));
                runOnUiThread(() -> {
                    if (isDestroyed()) return; resetting = false; setBusy(false);
                    if (reset || (sameSource && !store.demo && !initial) || (initial && parsed.values().stream().anyMatch(s -> s.employee.equals(store.owner)))) {
                        if (mutate(() -> store.applySource(parsed, candidate, "Google Таблица / CSV", reset))) {
                            sourceDraft = candidate;
                            toast(reset ? "График восстановлен из таблицы" : parsed.isEmpty() ? "В таблице нет смен. Сохранённый график не изменён" : "График обновлён. История и ручные правки сохранены");
                        }
                    } else confirmImport(parsed, candidate, "Google / CSV по ссылке");
                });
            } catch (Exception e) { importError(e); }
        });
    }

    private void importError(Exception e) {
        runOnUiThread(() -> {
            if (isDestroyed()) return; resetting = false; setBusy(false);
            String message = e.getMessage();
            if (e instanceof java.net.UnknownHostException) message = "Не удалось подключиться. Проверьте интернет и адрес таблицы";
            else if (e instanceof java.net.SocketTimeoutException) message = "Источник не ответил вовремя. Попробуйте ещё раз";
            error((message == null ? "Не удалось прочитать источник" : message) + "\n\nДанные на телефоне не изменены.");
        });
    }

    private void confirmImport(Map<String, Schedule.Shift> parsed, String url, String name) {
        TreeSet<String> names = new TreeSet<>(); for (Schedule.Shift s : parsed.values()) names.add(s.employee);
        LinearLayout form = column(); form.setPadding(dp(22), dp(8), dp(22), dp(8));
        form.addView(text("Смен: " + parsed.size() + " · сотрудников: " + names.size() +
                (parsed.isEmpty() ? "\n\nФайл не содержит смен. Сохранённая история не будет удалена." : "\n\nОбновятся только месяцы, указанные в файле. Остальные месяцы останутся в архиве.") +
                (store.demo ? "\nДемонстрационный график будет заменён." : ""), 15, INK, false));
        form.addView(text("Локальные правки сохранятся: " + store.edits.size() + ". Удалить их все можно только полным сбросом в настройках.", 13, MUTED, false));
        List<String> candidates = new ArrayList<>(names);
        form.addView(text("Выберите себя в таблице", 14, INK, true));
        Spinner ownerPicker = new Spinner(this);
        ArrayAdapter<String> ownerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, candidates);
        ownerPicker.setAdapter(ownerAdapter); if (candidates.contains(store.owner)) ownerPicker.setSelection(candidates.indexOf(store.owner));
        form.addView(ownerPicker, new LinearLayout.LayoutParams(-1, dp(52)));
        new AlertDialog.Builder(this).setTitle("Загрузить график?").setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton("Загрузить", (d, w) -> {
                    if (mutate(() -> {
                        store.applySource(parsed, url, name, false);
                        if (!candidates.isEmpty()) store.owner = candidates.get(ownerPicker.getSelectedItemPosition());
                    })) {
                        employee = store.owner; sourceDraft = url; screen = 0;
                        if (!parsed.isEmpty() && Schedule.select(parsed, month, employee).isEmpty()) {
                            day = parsed.values().stream().filter(s -> s.employee.equals(employee)).map(s -> s.date).min(LocalDate::compareTo).orElse(month.atDay(1)); month = YearMonth.from(day);
                        }
                        render(); toast("График загружен");
                    }
                }).show();
    }

    private void showEdits() {
        List<String> keys = new ArrayList<>(store.edits.keySet()); String[] labels = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i); Schedule.Shift s = store.edits.get(key);
            labels[i] = key.replace('\t', ' ') + (s == null ? " · удалено" : " · " + s.timeRange());
        }
        new AlertDialog.Builder(this).setTitle("Выберите правку для отмены").setItems(labels, (d, which) -> new AlertDialog.Builder(this)
                .setTitle("Отменить локальную правку?").setMessage(labels[which] + "\nБудут восстановлены текущие данные источника. Локально добавленная смена исчезнет.")
                .setNegativeButton("Назад", null).setPositiveButton("Отменить правку", (dialog, w) -> mutate(() -> store.edits.remove(keys.get(which)))).show())
                .setNegativeButton("Закрыть", null).show();
    }

    private boolean mutate(Runnable action) {
        String snapshot = null;
        try {
            snapshot = store.encode().toString(); action.run(); store.save(); render(); return true;
        } catch (Exception e) {
            if (snapshot != null) try { store.decode(snapshot); } catch (Exception ignored) { /* in-memory snapshot is valid */ }
            error(e.getMessage() == null ? "Не удалось сохранить изменения" : e.getMessage()); return false;
        }
    }

    private void export(int request) {
        String exportContent;
        if (request == EXPORT_CSV) {
            // Materialize automatic lunches so another installation reproduces these totals.
            List<Schedule.Shift> resolved = new ArrayList<>();
            for (Schedule.Shift s : store.all().values()) if (s.employee.equals(store.owner)) resolved.add(new Schedule.Shift(s.employee, s.department, s.date, s.start, s.end, s.nextDay, s.lunch(store.rule)));
            resolved.sort(java.util.Comparator.comparing((Schedule.Shift s) -> s.date).thenComparing(s -> s.employee)); exportContent = CsvSchedule.write(resolved);
        } else exportContent = "employee,department,date,start,end,break_minutes,next_day\r\nАнна Смирнова,Торговый зал," + month.atDay(1) + ",09:30,19:00,,false\r\nАнна Смирнова,Торговый зал," + month.atDay(2) + ",14:00,22:00,,false\r\n";
        // Keep only a path in instance state: a large CSV can exceed Android's Binder limit.
        java.io.File staged = new java.io.File(getCacheDir(), "pending-export.csv");
        try {
            java.nio.file.Files.write(staged.toPath(), ("\uFEFF" + exportContent).getBytes(StandardCharsets.UTF_8));
            pendingExport = staged.getAbsolutePath();
        } catch (java.io.IOException e) { error("Не удалось подготовить экспорт: " + e.getMessage()); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, request == EXPORT_TEMPLATE ? "smena-template.csv" : "smena-export.csv");
        launchDocument(intent, request);
    }

    private void launchDocument(Intent intent, int request) {
        try { startActivityForResult(intent, request); }
        catch (android.content.ActivityNotFoundException e) { error("На устройстве не найдено приложение для выбора файлов"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_CSV) {
            setBusy(true);
            io.execute(() -> {
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    Map<String, Schedule.Shift> parsed = CsvSchedule.read(GoogleSource.readLimited(stream));
                    runOnUiThread(() -> { if (isDestroyed()) return; setBusy(false); confirmImport(parsed, "", "CSV с телефона"); });
                } catch (Exception e) { importError(e); }
            });
        } else if ((request == EXPORT_CSV || request == EXPORT_TEMPLATE) && pendingExport != null) {
            String payload = pendingExport; pendingExport = null;
            io.execute(() -> {
                try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) throw new java.io.IOException("Не удалось открыть файл для записи");
                    java.nio.file.Files.copy(new java.io.File(payload).toPath(), stream);
                    runOnUiThread(() -> { if (!isDestroyed()) toast("CSV сохранён"); });
                } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) error("Не удалось сохранить CSV: " + e.getMessage()); }); }
            });
        }
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); v.setLineSpacing(dp(2), 1); return v;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button v = new Button(this); v.setText(label); v.setTextSize(14); v.setAllCaps(false); v.setTextColor(primary ? (dark ? GREEN : WHITE) : INK);
        v.setBackground(background(primary ? (dark ? LIME : GREEN) : 0x00000000, 12)); v.setMinHeight(dp(48)); v.setMinimumHeight(dp(48));
        v.setMinWidth(dp(48)); v.setMinimumWidth(dp(48)); v.setPadding(dp(12), dp(8), dp(12), dp(8)); v.setOnClickListener(view -> action.run()); return v;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(16), dp(16), dp(16), dp(16)); v.setBackground(background(CARD, 16)); return v; }
    private void gap(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private void note(String message, Runnable click) {
        TextView v = text(message, 12, INK, false); v.setPadding(dp(12), dp(10), dp(12), dp(10)); v.setBackground(background(NOTE, 10));
        v.setOnClickListener(view -> click.run()); content.addView(v);
    }
    private EditText input(LinearLayout parent, String label, String value, String hint, int type) {
        TextView caption = text(label, 12, MUTED, true); parent.addView(caption);
        EditText field = new EditText(this); field.setTextSize(16); field.setTextColor(INK); field.setInputType(type);
        field.setSingleLine(true); field.setText(value); field.setHint(hint); field.setContentDescription(label);
        parent.addView(field, new LinearLayout.LayoutParams(-1, dp(52))); gap(parent, 8); return field;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void error(String message) { if (!isDestroyed()) new AlertDialog.Builder(this).setTitle("Не получилось").setMessage(message).setPositiveButton("Понятно", null).show(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
