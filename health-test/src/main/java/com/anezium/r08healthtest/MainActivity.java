package com.anezium.r08healthtest;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.anezium.r08healthtest.gesture.GestureBridge;
import com.anezium.r08healthtest.gesture.GestureState;
import com.anezium.ringhealth.HealthSample;
import com.anezium.ringhealth.RingHealthBackend;
import com.anezium.ringhealth.RingHealthSnapshot;
import com.anezium.ringhealth.SleepSession;
import com.anezium.ringhealth.domain.AutoMeasurementSettings;
import com.anezium.ringhealth.domain.ConnectionState;
import com.anezium.ringhealth.domain.HealthMetric;

import java.text.DateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;

public final class MainActivity extends Activity implements RingHealthBackend.Listener,
        GestureState.Listener {
    private static final int BLE_PERMISSION_REQUEST = 1208;
    private static final int[] PERIODIC_SYNC_INTERVALS = {30, 60, 120};

    private final EnumMap<HealthMetric, MetricViews> metricViews = new EnumMap<>(HealthMetric.class);
    private RingHealthBackend repository;
    private TextView connectionView;
    private TextView gestureView;
    private TextView syncStatusView;
    private TextView periodicSyncStatusView;
    private Button sleepSyncToggle;
    private TextView sleepView;
    private TextView autoSettingsStatusView;
    private Button syncButton;
    private Button periodicSyncToggle;
    private Button periodicSyncApply;
    private Spinner periodicSyncInterval;
    private Spinner historyFilter;
    private TextView historyView;
    private TextView diagnosticsView;
    private RingHealthSnapshot lastSnapshot;
    private int renderedPeriodicSyncInterval = -1;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = RingHealthRuntime.repository(this);
        setContentView(buildUi());
        repository.addListener(this);
        GestureState.get().addListener(this);
        ensurePermissionsAndStart();
    }

    @Override protected void onResume() {
        super.onResume();
        repository.refreshData();
    }

    @Override protected void onStop() {
        if (lastSnapshot != null && lastSnapshot.activeMeasurement != null) {
            repository.cancelMeasurement("UI stopped");
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        repository.removeListener(this);
        GestureState.get().removeListener(this);
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (GestureBridge.get(this).handle(event, "activity")) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void onSnapshot(RingHealthSnapshot snapshot) {
        lastSnapshot = snapshot;
        render(snapshot);
    }

    @Override public void onGestureSnapshot(GestureState.Snapshot snapshot) {
        gestureView.setText("Последний жест: " + snapshot.event() + "\n"
                + "keyCode: " + (snapshot.keyCode() < 0 ? "—" : snapshot.keyCode())
                + " · count: " + snapshot.count() + "\n"
                + "time: " + formatTime(snapshot.observedAtEpochMs()));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == BLE_PERMISSION_REQUEST && hasBlePermissions()) {
            startTransportService();
            repository.onPermissionsChanged();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        root.setBackgroundColor(Color.rgb(245, 247, 244));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("R08 Health Test", 26, Color.rgb(16, 48, 38));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView disclaimer = text("Wellness-значения ориентировочные и не предназначены для медицинской диагностики.",
                13, Color.DKGRAY);
        disclaimer.setPadding(0, dp(5), 0, dp(14));
        root.addView(disclaimer);

        connectionView = cardText();
        root.addView(connectionView);

        Button accessibility = new Button(this);
        accessibility.setText("Включить перехват жестов");
        accessibility.setAllCaps(false);
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));

        gestureView = cardText();
        root.addView(gestureView);

        for (HealthMetric metric : HealthMetric.values()) {
            MetricViews views = metricCard(metric);
            metricViews.put(metric, views);
            root.addView(views.container, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        }

        autoSettingsStatusView = text("Автоизмерения: ожидание кольца", 13, Color.DKGRAY);
        autoSettingsStatusView.setPadding(0, dp(8), 0, 0);
        root.addView(autoSettingsStatusView);

        syncButton = new Button(this);
        syncButton.setText("Синхронизировать с кольцом");
        syncButton.setAllCaps(false);
        syncButton.setOnClickListener(v -> repository.synchronizeToday());
        root.addView(syncButton, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        syncStatusView = text("Sync: Idle", 14, Color.DKGRAY);
        root.addView(syncStatusView);

        TextView periodicSyncTitle = text("Фоновый синк истории", 20, Color.rgb(16, 48, 38));
        periodicSyncTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(periodicSyncTitle);
        TextView periodicSyncHint = text(
                "30 мин — рекомендовано для HUD и совпадает с периодом SpO₂; "
                        + "60/120 мин экономят батарею, но значения будут менее свежими.",
                13, Color.DKGRAY);
        root.addView(periodicSyncHint);
        periodicSyncInterval = new Spinner(this);
        String[] periodicOptions = {
                "30 мин — HUD (рекомендуется)",
                "60 мин — экономный",
                "120 мин — минимальный расход"
        };
        periodicSyncInterval.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, periodicOptions));
        root.addView(periodicSyncInterval);
        periodicSyncApply = new Button(this);
        periodicSyncApply.setText("Применить период синка");
        periodicSyncApply.setAllCaps(false);
        periodicSyncApply.setOnClickListener(v -> {
            int position = periodicSyncInterval.getSelectedItemPosition();
            if (position >= 0 && position < PERIODIC_SYNC_INTERVALS.length) {
                repository.setPeriodicSyncInterval(PERIODIC_SYNC_INTERVALS[position]);
            }
        });
        root.addView(periodicSyncApply, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        periodicSyncToggle = new Button(this);
        periodicSyncToggle.setText("Выключить фоновый синк");
        periodicSyncToggle.setAllCaps(false);
        periodicSyncToggle.setOnClickListener(v -> {
            RingHealthSnapshot snapshot = lastSnapshot;
            if (snapshot != null) repository.setPeriodicSyncEnabled(!snapshot.periodicSyncEnabled);
        });
        root.addView(periodicSyncToggle, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        periodicSyncStatusView = text("Фоновый sync: ожидание", 13, Color.DKGRAY);
        root.addView(periodicSyncStatusView);

        TextView sleepTitle = text("Сон (автоматически)", 20, Color.rgb(16, 48, 38));
        sleepTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(sleepTitle);
        TextView sleepHint = text(
                "R08 сам определяет ночной и дневной сон. Переключатель ниже управляет только "
                        + "загрузкой истории в приложение, а не измерением на кольце.",
                13, Color.DKGRAY);
        root.addView(sleepHint);
        sleepSyncToggle = new Button(this);
        sleepSyncToggle.setAllCaps(false);
        sleepSyncToggle.setText("Выключить импорт сна");
        sleepSyncToggle.setOnClickListener(v -> {
            RingHealthSnapshot snapshot = lastSnapshot;
            if (snapshot != null) repository.setSleepSyncEnabled(!snapshot.sleepSyncEnabled);
        });
        root.addView(sleepSyncToggle, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 4));
        sleepView = cardText();
        root.addView(sleepView);

        TextView historyTitle = text("История", 20, Color.rgb(16, 48, 38));
        historyTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(historyTitle);
        historyFilter = new Spinner(this);
        String[] options = {"Все метрики", "ЧСС", "SpO₂", "Стресс", "HRV", "Температура"};
        historyFilter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        historyFilter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (lastSnapshot != null) renderHistory(lastSnapshot);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(historyFilter);
        historyView = cardText();
        root.addView(historyView);

        TextView diagTitle = text("Диагностика", 20, Color.rgb(16, 48, 38));
        diagTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(diagTitle);
        diagnosticsView = text("—", 11, Color.DKGRAY);
        diagnosticsView.setTextIsSelectable(true);
        diagnosticsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(diagnosticsView);
        return scroll;
    }

    private MetricViews metricCard(HealthMetric metric) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(cardBackground());
        TextView title = text(metric.title, 18, Color.rgb(16, 48, 38));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        TextView value = text("—", 24, Color.BLACK);
        TextView status = text("Idle", 13, Color.DKGRAY);
        Button action = new Button(this);
        action.setAllCaps(false);
        action.setText("Измерить");
        action.setOnClickListener(v -> {
            RingHealthSnapshot snapshot = lastSnapshot;
            if (snapshot != null && snapshot.activeMeasurement == metric) {
                repository.cancelMeasurement("Cancelled");
            } else {
                repository.measure(metric);
            }
        });
        TextView autoStatus = null;
        Button autoToggle = null;
        card.addView(title);
        card.addView(value);
        card.addView(status);
        card.addView(action, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        if (metric.hasAutoSettings()) {
            autoStatus = text("Авто: чтение настройки…", 13, Color.DKGRAY);
            autoStatus.setPadding(0, dp(8), 0, 0);
            autoToggle = new Button(this);
            autoToggle.setAllCaps(false);
            autoToggle.setText("Включить авто");
            autoToggle.setOnClickListener(v -> {
                RingHealthSnapshot snapshot = lastSnapshot;
                if (snapshot == null) return;
                AutoMeasurementSettings.MetricSetting setting =
                        snapshot.autoMeasurementSettings.forMetric(metric);
                if (setting.loaded()) repository.setAutoMeasurement(metric, !setting.enabled());
            });
            card.addView(autoStatus);
            card.addView(autoToggle, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        }
        Spinner intervalSpinner = null;
        Button intervalButton = null;
        if (metric == HealthMetric.HEART_RATE) {
            intervalSpinner = new Spinner(this);
            card.addView(intervalSpinner);
            intervalButton = new Button(this);
            intervalButton.setAllCaps(false);
            intervalButton.setText("Применить интервал ЧСС");
            Spinner finalIntervalSpinner = intervalSpinner;
            intervalButton.setOnClickListener(v -> {
                Object selected = finalIntervalSpinner.getSelectedItem();
                if (selected == null) return;
                String valueText = selected.toString().replace(" мин", "");
                try { repository.setHeartRateInterval(Integer.parseInt(valueText)); }
                catch (NumberFormatException ignored) {}
            });
            card.addView(intervalButton, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        }
        return new MetricViews(card, value, status, action, autoStatus, autoToggle,
                intervalSpinner, intervalButton);
    }

    private void render(RingHealthSnapshot snapshot) {
        connectionView.setText("R08: " + snapshot.ringName + "\n"
                + "Address: " + emptyDash(snapshot.ringAddress) + "\n"
                + "State: " + snapshot.connectionState + "\n"
                + "Bond: " + yesNo(snapshot.bonded) + " · GATT: " + yesNo(snapshot.gattConnected)
                + " · Notify: " + yesNo(snapshot.notificationsReady) + "\n"
                + "Battery: " + (snapshot.batteryPercent < 0 ? "—" : snapshot.batteryPercent + "%"));

        boolean ready = snapshot.connectionState == ConnectionState.READY;
        for (HealthMetric metric : HealthMetric.values()) {
            MetricViews views = metricViews.get(metric);
            HealthSample sample = snapshot.latest.get(metric);
            views.value.setText(sample == null ? "—" : formatValue(metric, sample.value()));
            String metricStatus = snapshot.activeMeasurement == metric ? snapshot.measurementStatus
                    : snapshot.activeMeasurement == null ? snapshot.measurementStatus : "Idle";
            if (!snapshot.capabilities.supportsManual(metric)) metricStatus = "Unsupported";
            String timestamp = sample == null ? "" : " · " + formatTime(sample.observedAtEpochMs());
            views.status.setText(metricStatus + timestamp);
            boolean active = snapshot.activeMeasurement == metric;
            views.button.setText(active ? "Отменить" : "Измерить");
            views.button.setEnabled(active || (ready && !snapshot.syncing
                    && snapshot.activeMeasurement == null && snapshot.capabilities.supportsManual(metric)));
            if (metric.hasAutoSettings()) {
                AutoMeasurementSettings.MetricSetting auto =
                        snapshot.autoMeasurementSettings.forMetric(metric);
                if (!auto.loaded()) {
                    views.autoStatus.setText("Авто: настройка не прочитана");
                } else {
                    String interval = auto.intervalMinutes() > 0
                            ? " · интервал " + auto.intervalMinutes() + " мин"
                            : metric == HealthMetric.TEMPERATURE ? " · интервал firmware" : "";
                    views.autoStatus.setText("Авто: " + (auto.enabled() ? "включено" : "выключено")
                            + interval);
                }
                views.autoToggle.setText(auto.enabled() ? "Выключить авто" : "Включить авто");
                views.autoToggle.setEnabled(ready && auto.loaded()
                        && !snapshot.autoMeasurementSettings.updating && !snapshot.syncing
                        && snapshot.activeMeasurement == null);
                if (metric == HealthMetric.HEART_RATE && views.intervalSpinner != null) {
                    configureHeartIntervals(views, auto);
                    boolean intervalsAvailable = views.intervalSpinner.getAdapter() != null
                            && views.intervalSpinner.getAdapter().getCount() > 0;
                    views.intervalSpinner.setEnabled(ready && auto.loaded()
                            && !snapshot.autoMeasurementSettings.updating);
                    views.intervalButton.setEnabled(ready && auto.loaded() && intervalsAvailable
                            && !snapshot.autoMeasurementSettings.updating && !snapshot.syncing
                            && snapshot.activeMeasurement == null);
                }
            }
        }
        autoSettingsStatusView.setText("Автоизмерения: " + snapshot.autoMeasurementSettings.status);
        syncButton.setEnabled(ready && !snapshot.syncing && snapshot.activeMeasurement == null);
        syncStatusView.setText("Sync: " + snapshot.syncStatus);
        renderPeriodicSync(snapshot, ready);
        renderSleep(snapshot);
        renderHistory(snapshot);
        StringBuilder logs = new StringBuilder();
        int count = 0;
        for (String line : snapshot.diagnostics) {
            if (count++ >= 18) break;
            logs.append(line).append('\n');
        }
        diagnosticsView.setText(logs.length() == 0 ? "—" : logs.toString().trim());
    }

    private void renderPeriodicSync(RingHealthSnapshot snapshot, boolean ready) {
        if (snapshot.periodicSyncIntervalMinutes != renderedPeriodicSyncInterval) {
            renderedPeriodicSyncInterval = snapshot.periodicSyncIntervalMinutes;
            for (int index = 0; index < PERIODIC_SYNC_INTERVALS.length; index++) {
                if (PERIODIC_SYNC_INTERVALS[index] == renderedPeriodicSyncInterval) {
                    periodicSyncInterval.setSelection(index);
                    break;
                }
            }
        }
        periodicSyncToggle.setText(snapshot.periodicSyncEnabled
                ? "Выключить фоновый синк" : "Включить фоновый синк");
        periodicSyncToggle.setEnabled(!snapshot.syncing);
        periodicSyncApply.setEnabled(!snapshot.syncing);
        periodicSyncInterval.setEnabled(!snapshot.syncing);
        String next = snapshot.periodicSyncEnabled
                ? formatTime(snapshot.nextPeriodicSyncAt) : "—";
        String metricTimes = "ЧСС: " + formatTime(snapshot.lastHistorySyncAt.getOrDefault(
                HealthMetric.HEART_RATE, 0L))
                + " · SpO₂: " + formatTime(snapshot.lastHistorySyncAt.getOrDefault(
                HealthMetric.SPO2, 0L))
                + " · Температура: " + formatTime(snapshot.lastHistorySyncAt.getOrDefault(
                HealthMetric.TEMPERATURE, 0L));
        periodicSyncStatusView.setText("Фоновый sync: "
                + (snapshot.periodicSyncEnabled ? "включён" : "выключен")
                + " · каждые " + snapshot.periodicSyncIntervalMinutes + " мин\n"
                + "Состояние: " + snapshot.periodicSyncStatus + "\n"
                + "Последний auto: " + formatTime(snapshot.lastPeriodicSyncAt) + "\n"
                + "По метрикам: " + metricTimes + "\n"
                + "Сон: " + formatTime(snapshot.lastSleepSyncAt) + "\n"
                + "Следующий: " + next
                + (ready ? "" : " · после восстановления связи"));
    }

    private void renderSleep(RingHealthSnapshot snapshot) {
        sleepSyncToggle.setText(snapshot.sleepSyncEnabled
                ? "Выключить импорт сна" : "Включить импорт сна");
        sleepSyncToggle.setEnabled(!snapshot.syncing);
        if (!snapshot.capabilities.newSleepProtocol) {
            sleepView.setText("Новый протокол сна не заявлен кольцом");
            return;
        }
        SleepSession latestNight = null;
        int napCount = 0;
        int napMinutes = 0;
        for (SleepSession session : snapshot.sleepHistory) {
            if (session.kind() == SleepSession.Kind.NIGHT && latestNight == null) {
                latestNight = session;
            } else if (session.kind() == SleepSession.Kind.NAP) {
                napCount++;
                napMinutes += session.totalSleepMinutes();
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Импорт: ").append(snapshot.sleepSyncEnabled ? "включён" : "выключен")
                .append(" · последний sync: ").append(formatTime(snapshot.lastSleepSyncAt))
                .append('\n');
        if (latestNight == null) {
            out.append("Ночных данных пока нет. Кольцо измеряет сон автоматически.");
        } else {
            out.append("Последняя ночь: ").append(formatTime(latestNight.startEpochMs()))
                    .append(" — ").append(formatTime(latestNight.endEpochMs())).append('\n')
                    .append("Сон: ").append(formatMinutes(latestNight.totalSleepMinutes()))
                    .append(" · лёгкий ").append(formatMinutes(latestNight.lightMinutes()))
                    .append(" · глубокий ").append(formatMinutes(latestNight.deepMinutes()))
                    .append(" · REM ").append(formatMinutes(latestNight.remMinutes()))
                    .append(" · бодрствование ").append(formatMinutes(latestNight.awakeMinutes()));
        }
        if (napCount > 0) {
            out.append("\nДневной сон в загруженной истории: ").append(napCount)
                    .append(" · ").append(formatMinutes(napMinutes));
        }
        sleepView.setText(out.toString());
    }

    private void renderHistory(RingHealthSnapshot snapshot) {
        int selection = historyFilter == null ? 0 : historyFilter.getSelectedItemPosition();
        HealthMetric filter = switch (selection) {
            case 1 -> HealthMetric.HEART_RATE;
            case 2 -> HealthMetric.SPO2;
            case 3 -> HealthMetric.STRESS;
            case 4 -> HealthMetric.HRV;
            case 5 -> HealthMetric.TEMPERATURE;
            default -> null;
        };
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (HealthSample sample : snapshot.history) {
            HealthMetric metric = sample.metric();
            if (filter != null && filter != metric) continue;
            out.append(formatTime(sample.observedAtEpochMs())).append("  ")
                    .append(metric.title).append("  ")
                    .append(formatValue(metric, sample.value())).append("  ")
                    .append(sample.source()).append('\n');
            if (++shown >= 50) break;
        }
        historyView.setText(out.length() == 0 ? "Пока нет данных" : out.toString().trim());
    }

    private void configureHeartIntervals(MetricViews views,
                                         AutoMeasurementSettings.MetricSetting setting) {
        if (!setting.loaded()) return;
        int[] intervals = RingHealthBackend.supportedHeartRateIntervals(
                setting.minimumIntervalMinutes());
        String signature = setting.intervalMinutes() + ":" + setting.minimumIntervalMinutes();
        if (signature.equals(views.intervalSignature)) return;
        views.intervalSignature = signature;
        String[] labels = new String[intervals.length];
        int selected = 0;
        for (int index = 0; index < intervals.length; index++) {
            labels[index] = intervals[index] + " мин";
            if (intervals[index] == setting.intervalMinutes()) selected = index;
        }
        views.intervalSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (labels.length > 0) views.intervalSpinner.setSelection(selected);
    }

    private void ensurePermissionsAndStart() {
        if (hasBlePermissions()) {
            startTransportService();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, BLE_PERMISSION_REQUEST);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, BLE_PERMISSION_REQUEST);
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startTransportService() {
        try { RingHealthService.start(this); }
        catch (RuntimeException error) {
            connectionView.setText("Не удалось запустить BLE service: " + error.getMessage());
        }
    }

    private TextView cardText() {
        TextView view = text("—", 14, Color.rgb(35, 45, 41));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(cardBackground());
        return view;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), Color.rgb(215, 224, 219));
        return drawable;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String yesNo(boolean value) { return value ? "yes" : "no"; }
    private static String emptyDash(String value) { return value == null || value.isEmpty() ? "—" : value; }

    private static String formatTime(long epochMs) {
        if (epochMs <= 0) return "—";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(epochMs));
    }

    private static String formatValue(HealthMetric metric, double value) {
        return metric == HealthMetric.TEMPERATURE
                ? String.format(Locale.getDefault(), "%.1f %s", value, metric.unit)
                : String.format(Locale.getDefault(), "%.0f %s", value, metric.unit);
    }

    private static String formatMinutes(int minutes) {
        if (minutes < 60) return minutes + " мин";
        return String.format(Locale.getDefault(), "%d ч %02d мин", minutes / 60, minutes % 60);
    }

    private static final class MetricViews {
        final LinearLayout container;
        final TextView value;
        final TextView status;
        final Button button;
        final TextView autoStatus;
        final Button autoToggle;
        final Spinner intervalSpinner;
        final Button intervalButton;
        String intervalSignature = "";

        MetricViews(LinearLayout container, TextView value, TextView status, Button button,
                    TextView autoStatus, Button autoToggle, Spinner intervalSpinner,
                    Button intervalButton) {
            this.container = container;
            this.value = value;
            this.status = status;
            this.button = button;
            this.autoStatus = autoStatus;
            this.autoToggle = autoToggle;
            this.intervalSpinner = intervalSpinner;
            this.intervalButton = intervalButton;
        }
    }
}
