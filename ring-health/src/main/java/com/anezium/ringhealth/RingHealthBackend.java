package com.anezium.ringhealth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.anezium.ringhealth.domain.Capabilities;
import com.anezium.ringhealth.domain.AutoMeasurementSettings;
import com.anezium.ringhealth.domain.ConnectionState;
import com.anezium.ringhealth.domain.HealthMetric;
import com.anezium.ringhealth.internal.protocol.ControlProtocol;
import com.anezium.ringhealth.internal.protocol.LargeDataProtocol;
import com.anezium.ringhealth.internal.protocol.LegacyHistoryProtocol;
import com.anezium.ringhealth.internal.protocol.SleepProtocol;
import com.anezium.ringhealth.internal.protocol.StepProtocol;
import com.anezium.ringhealth.internal.storage.HealthDao;
import com.anezium.ringhealth.internal.storage.HealthBackupCodec;
import com.anezium.ringhealth.internal.storage.HealthDatabase;
import com.anezium.ringhealth.internal.storage.HealthSampleEntity;
import com.anezium.ringhealth.internal.storage.SleepSessionEntity;
import com.anezium.ringhealth.internal.storage.StepDayEntity;
import com.anezium.ringhealth.internal.storage.SyncRunEntity;
import com.anezium.ringhealth.internal.transport.SerializedQueue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.lang.reflect.Method;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@SuppressLint("MissingPermission")
public class RingHealthBackend {
    public interface Listener { void onSnapshot(RingHealthSnapshot snapshot); }

    /** Optional host-owned startup command, serialized after Health setup. */
    protected static final class HostBootstrapCommand {
        private final String label;
        private final int responseOpcode;
        private final byte[] frame;

        public HostBootstrapCommand(String label, int responseOpcode, byte[] frame) {
            this.label = label;
            this.responseOpcode = responseOpcode;
            this.frame = frame.clone();
        }
    }

    private interface ResponseHandler { boolean onResponse(byte[] frame); }

    private enum HistoryKind { INTERVAL, LEGACY_HEART, LEGACY_SPO2, LEGACY_TEMPERATURE }
    private enum SyncTrigger { MANUAL, PERIODIC }

    private static final String TAG = "R08Health";
    private static final UUID CONTROL_SERVICE = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CONTROL_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CONTROL_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID LARGE_SERVICE = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7");
    private static final UUID LARGE_NOTIFY = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7");
    private static final UUID LARGE_WRITE = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 25_000L;
    private static final long GATT_OPERATION_TIMEOUT_MS = 8_000L;
    private static final long CONTROL_TIMEOUT_MS = 8_000L;
    private static final long FINAL_MEASUREMENT_TIMEOUT_MS = 3_000L;
    private static final int FINAL_MEASUREMENT_ATTEMPTS = 2;
    private static final long HISTORY_TIMEOUT_MS = 15_000L;
    private static final long SLEEP_COMPANION_GRACE_MS = 5_000L;
    private static final int SLEEP_MIN_SYNC_INTERVAL_MINUTES = 120;
    // The database is never trimmed. Only the in-memory graph window is bounded.
    private static final long CHART_HISTORY_WINDOW_MS = 31L * 24L * 60L * 60L * 1000L;
    private static final long[] RECONNECT_BACKOFF_MS = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L};
    private static final String PREF_PERIODIC_SYNC_ENABLED = "periodic_sync_enabled";
    private static final String PREF_PERIODIC_SYNC_INTERVAL = "periodic_sync_interval_minutes";
    private static final String PREF_PERIODIC_SYNC_LAST = "periodic_sync_last_epoch_ms";
    private static final String PREF_PERIODIC_SYNC_NEXT = "periodic_sync_next_epoch_ms";
    private static final String PREF_PERIODIC_SYNC_PENDING = "periodic_sync_pending";
    private static final String PREF_METRIC_SYNC_LAST_PREFIX = "metric_sync_last_epoch_ms_";
    private static final String PREF_SLEEP_SYNC_ENABLED = "sleep_sync_enabled";
    private static final String PREF_SLEEP_SYNC_LAST = "sleep_sync_last_epoch_ms";
    private static final String PREF_STEP_SYNC_LAST = "step_sync_last_epoch_ms";
    private static final String PREF_STEP_HISTORY_DAY = "step_history_last_epoch_day";

    private final Context context;
    private final HandlerThread workerThread = new HandlerThread("R08HealthTransport");
    private final Handler worker;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final HealthDao dao;
    private final SharedPreferences preferences;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Queue<ControlRequest> controlRequests = new ArrayDeque<>();
    private final LargeDataProtocol.Reassembler largeReassembler = new LargeDataProtocol.Reassembler();
    private final EnumMap<HealthMetric, HealthSampleEntity> latest = new EnumMap<>(HealthMetric.class);
    private final ArrayList<HealthSampleEntity> history = new ArrayList<>();
    private final ArrayList<SleepSessionEntity> sleepHistory = new ArrayList<>();
    private final ArrayList<StepDayEntity> stepHistory = new ArrayList<>();
    private final ArrayDeque<String> diagnostics = new ArrayDeque<>();

    private SerializedQueue<GattOperation> gattQueue;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice targetDevice;
    private BluetoothGattCharacteristic controlWrite;
    private BluetoothGattCharacteristic controlNotify;
    private BluetoothGattCharacteristic largeWrite;
    private BluetoothGattCharacteristic largeNotify;
    private boolean started;
    private boolean receiverRegistered;
    private boolean scanning;
    private int notificationDescriptorsRemaining;
    private int mtu = 23;
    private int reconnectAttempt;

    private ConnectionState connectionState = ConnectionState.UNPAIRED;
    private String ringName = "R08";
    private String ringAddress = "";
    private boolean bonded;
    private boolean gattConnected;
    private boolean notificationsReady;
    private int batteryPercent = -1;
    private boolean batteryCharging;
    private Capabilities capabilities = Capabilities.UNKNOWN;
    private Capabilities advertisedCapabilities = Capabilities.UNKNOWN;
    private ControlProtocol.HeartRateSettings heartRateSettings;
    private ControlProtocol.Spo2Settings spo2Settings;
    private ControlProtocol.TemperatureSettings temperatureSettings;
    private boolean autoSettingsUpdating;
    private String autoSettingsStatus = "Waiting for ring";
    private ControlRequest activeControl;
    private HealthMetric activeMeasurement;
    private int measurementLastRaw;
    private int measurementFinalizeAttempts;
    private String measurementStatus = "Idle";
    private long measurementStartedAtEpochMs;
    private long measurementDeadlineAtEpochMs;
    private boolean syncing;
    private String syncStatus = "Idle";
    private SyncTrigger activeSyncTrigger;
    private final PendingManualSyncQueue manualSyncQueue = new PendingManualSyncQueue();
    private boolean periodicSyncEnabled;
    private int periodicSyncIntervalMinutes;
    private String periodicSyncStatus;
    private long lastPeriodicSyncAt;
    private long nextPeriodicSyncAt;
    private PendingPeriodicSyncQueue periodicSyncQueue;
    private final EnumMap<HealthMetric, Long> lastHistorySyncAt =
            new EnumMap<>(HealthMetric.class);
    private final Queue<HealthMetric> syncMetrics = new ArrayDeque<>();
    private final EnumSet<HealthMetric> syncCompleted = EnumSet.noneOf(HealthMetric.class);
    private final EnumSet<HealthMetric> syncFailed = EnumSet.noneOf(HealthMetric.class);
    private boolean sleepSyncEnabled;
    private long lastSleepSyncAt;
    private long lastStepSyncAt;
    private long lastStepHistoryEpochDay;
    private int todaySteps;
    private boolean stepSyncQueued;
    private boolean stepSyncActive;
    private boolean stepCompleted;
    private boolean stepFailed;
    private boolean stepPersisting;
    private int stepHistoryDayOffset;
    private StepProtocol.DetailAssembler stepDetailAssembler;
    private boolean sleepSyncQueued;
    private boolean sleepSyncActive;
    private boolean sleepCompleted;
    private boolean sleepFailed;
    private boolean sleepNightReceived;
    private boolean sleepNapReceived;
    private boolean sleepPersisting;
    private SleepSessionEntity latestSleep;
    private final List<SleepProtocol.DecodedSession> sleepPendingSessions = new ArrayList<>();
    private HealthMetric historyMetric;
    private HistoryKind historyKind;
    private int historyAction;
    private int historyPage;
    private int historyFirstSampleIndex;
    private boolean historyPersisting;
    private final List<LargeDataProtocol.Sample> historyPendingSamples = new ArrayList<>();
    private LegacyHistoryProtocol.HeartAssembler legacyHeartAssembler;
    private SyncRunEntity syncRun;
    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        stopScan();
        diagnostic("scan timeout");
        scheduleReconnect("scan timeout");
    };

    private final Runnable gattOperationTimeout = () -> {
        GattOperation active = gattQueue.active();
        if (active == null) return;
        diagnostic("GATT timeout " + active.label);
        if (active.onFailure != null) active.onFailure.run();
        gattQueue.complete();
        disconnectAndRetry("GATT operation timeout");
    };

    private final Runnable controlTimeout = () -> {
        if (activeControl == null) return;
        ControlRequest timedOut = activeControl;
        activeControl = null;
        diagnostic("control timeout opcode=" + hexByte(timedOut.expectedOpcode));
        if (timedOut.onTimeout != null) timedOut.onTimeout.run();
        drainControlQueue();
    };

    private final Runnable measurementTimeout = this::finishTimedOutMeasurement;
    private final Runnable historyTimeout = () -> {
        if (sleepSyncActive) failSleepSync("response timeout");
        else failHistoryMetric("response timeout");
    };
    private final Runnable sleepCompanionTimeout = this::completeSleepSync;
    private final Runnable periodicSyncDue = this::handlePeriodicSyncDue;
    private final Runnable localMidnight = () -> {
        todaySteps = 0;
        publishSnapshot();
        scheduleLocalMidnightReset();
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
            BluetoothDevice device = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !matchesTarget(device)) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            worker.post(() -> onBondState(device, state));
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!matchesTarget(device)) return;
            worker.post(() -> {
                if (!scanning) return;
                stopScan();
                rememberTarget(device);
                bondOrConnect(device);
            });
        }

        @Override public void onScanFailed(int errorCode) {
            worker.post(() -> {
                scanning = false;
                diagnostic("scan failed code=" + errorCode);
                scheduleReconnect("scan failed");
            });
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            worker.post(() -> handleConnectionState(callbackGatt, status, newState));
        }

        @Override public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            worker.post(() -> handleServicesDiscovered(callbackGatt, status));
        }

        @Override public void onMtuChanged(BluetoothGatt callbackGatt, int value, int status) {
            worker.post(() -> {
                if (callbackGatt != gatt || !gattConnected) return;
                if (controlNotify == null || largeNotify == null) {
                    diagnostic("MTU callback before service discovery; deferring notify setup");
                    return;
                }
                worker.removeCallbacks(beginNotificationsFallback);
                mtu = status == BluetoothGatt.GATT_SUCCESS ? value : 23;
                diagnostic("MTU=" + mtu + " status=" + status);
                beginNotifications();
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt callbackGatt,
                                                BluetoothGattDescriptor descriptor, int status) {
            worker.post(() -> completeGattOperation(status, "descriptor " + descriptor.getUuid()));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                                    BluetoothGattCharacteristic characteristic, int status) {
            worker.post(() -> completeGattOperation(status, "characteristic " + characteristic.getUuid()));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                                      BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            dispatchNotification(characteristic.getUuid(), value == null ? null : value.clone());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                                      BluetoothGattCharacteristic characteristic,
                                                      byte[] value) {
            dispatchNotification(characteristic.getUuid(), value == null ? null : value.clone());
        }
    };

    private final Runnable beginNotificationsFallback = () -> {
        diagnostic("MTU callback timeout; using 23");
        mtu = 23;
        beginNotifications();
    };

    public RingHealthBackend(Context context) {
        this.context = context;
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        dao = HealthDatabase.get(context).healthDao();
        preferences = context.getSharedPreferences("r08-health", Context.MODE_PRIVATE);
        ringAddress = preferences.getString("ring_address", "");
        ringName = preferences.getString("ring_name", "R08");
        periodicSyncEnabled = preferences.getBoolean(PREF_PERIODIC_SYNC_ENABLED, true);
        periodicSyncIntervalMinutes = preferences.getInt(PREF_PERIODIC_SYNC_INTERVAL,
                PeriodicSyncPolicy.DEFAULT_INTERVAL_MINUTES);
        if (!PeriodicSyncPolicy.isSupportedInterval(periodicSyncIntervalMinutes)) {
            periodicSyncIntervalMinutes = PeriodicSyncPolicy.DEFAULT_INTERVAL_MINUTES;
        }
        lastPeriodicSyncAt = preferences.getLong(PREF_PERIODIC_SYNC_LAST, 0L);
        nextPeriodicSyncAt = preferences.getLong(PREF_PERIODIC_SYNC_NEXT, 0L);
        periodicSyncQueue = new PendingPeriodicSyncQueue(
                preferences.getBoolean(PREF_PERIODIC_SYNC_PENDING, false));
        sleepSyncEnabled = preferences.getBoolean(PREF_SLEEP_SYNC_ENABLED, true);
        lastSleepSyncAt = preferences.getLong(PREF_SLEEP_SYNC_LAST, 0L);
        lastStepSyncAt = preferences.getLong(PREF_STEP_SYNC_LAST, 0L);
        lastStepHistoryEpochDay = preferences.getLong(PREF_STEP_HISTORY_DAY, Long.MIN_VALUE);
        for (HealthMetric metric : HealthMetric.values()) {
            lastHistorySyncAt.put(metric,
                    preferences.getLong(metricSyncPreferenceKey(metric), 0L));
        }
        periodicSyncStatus = !periodicSyncEnabled ? "Disabled"
                : periodicSyncQueue.isPending() ? "Queued: restored after restart"
                : "Waiting for scheduled sync";
        gattQueue = new SerializedQueue<>(this::startGattOperation);
        refreshStorage();
        scheduleLocalMidnightReset();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        worker.post(this::publishSnapshot);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void start() {
        worker.post(() -> {
            scheduleLocalMidnightReset();
            if (!started) {
                started = true;
                initializeBluetooth();
            } else {
                connectIfPossible();
            }
            ensurePeriodicSyncScheduled();
        });
    }

    /** Stops BLE work owned by this backend. The same instance can be started again. */
    public void stop() {
        worker.post(() -> {
            started = false;
            worker.removeCallbacksAndMessages(null);
            stopScan();
            if (receiverRegistered) {
                try { context.unregisterReceiver(bondReceiver); }
                catch (IllegalArgumentException ignored) {}
                receiverRegistered = false;
            }
            if (gatt != null) {
                try { gatt.disconnect(); } catch (RuntimeException ignored) {}
            }
            closeGatt();
            gattConnected = false;
            notificationsReady = false;
            setState(ConnectionState.DISCONNECTED_RETRYING, "Stopped by host");
        });
    }

    public void onPermissionsChanged() { worker.post(this::connectIfPossible); }

    public void measure(HealthMetric metric) {
        worker.post(() -> startMeasurement(metric));
    }

    public void setAutoMeasurement(HealthMetric metric, boolean enabled) {
        worker.post(() -> updateAutoMeasurement(metric, enabled,
                metric == HealthMetric.HEART_RATE && heartRateSettings != null
                        ? heartRateSettings.intervalMinutes() : -1));
    }

    public void setHeartRateInterval(int intervalMinutes) {
        worker.post(() -> updateAutoMeasurement(HealthMetric.HEART_RATE,
                heartRateSettings != null && heartRateSettings.enabled(), intervalMinutes));
    }

    public void cancelMeasurement(String reason) {
        worker.post(() -> {
            if (activeMeasurement != null) finishMeasurement(reason, false, true);
        });
    }

    public void synchronizeToday() { worker.post(this::requestManualSync); }

    public void setPeriodicSyncEnabled(boolean enabled) {
        worker.post(() -> configurePeriodicSync(enabled, periodicSyncIntervalMinutes));
    }

    public void setPeriodicSyncInterval(int intervalMinutes) {
        worker.post(() -> configurePeriodicSync(periodicSyncEnabled, intervalMinutes));
    }

    public static boolean savedPeriodicSyncEnabled(Context context) {
        return healthPreferences(context).getBoolean(PREF_PERIODIC_SYNC_ENABLED, true);
    }

    public static int savedPeriodicSyncIntervalMinutes(Context context) {
        int interval = healthPreferences(context).getInt(PREF_PERIODIC_SYNC_INTERVAL,
                PeriodicSyncPolicy.DEFAULT_INTERVAL_MINUTES);
        return PeriodicSyncPolicy.isSupportedInterval(interval)
                ? interval : PeriodicSyncPolicy.DEFAULT_INTERVAL_MINUTES;
    }

    public static long savedNextPeriodicSyncAt(Context context) {
        return healthPreferences(context).getLong(PREF_PERIODIC_SYNC_NEXT, 0L);
    }

    public static long savedLastPeriodicSyncAt(Context context) {
        return healthPreferences(context).getLong(PREF_PERIODIC_SYNC_LAST, 0L);
    }

    public static long ensureSavedNextPeriodicSyncAt(Context context) {
        SharedPreferences preferences = healthPreferences(context);
        long next = preferences.getLong(PREF_PERIODIC_SYNC_NEXT, 0L);
        if (next > 0L) return next;
        next = PeriodicSyncPolicy.deadlineAfter(System.currentTimeMillis(),
                savedPeriodicSyncIntervalMinutes(context));
        preferences.edit().putLong(PREF_PERIODIC_SYNC_NEXT, next).apply();
        return next;
    }

    public void exportHealthData(File directory, Consumer<HealthBackupResult> callback) {
        databaseExecutor.execute(() -> {
            HealthBackupResult result;
            try {
                List<HealthSampleEntity> all = dao.allSamples();
                File file = HealthBackupCodec.write(directory, all, System.currentTimeMillis());
                result = HealthBackupResult.success(
                        "Exported " + all.size() + " samples", file.getName(), all.size());
            } catch (Exception failure) {
                Log.e(TAG, "Health export failed", failure);
                result = HealthBackupResult.error("Export failed: " + safeMessage(failure));
            }
            HealthBackupResult completed = result;
            main.post(() -> callback.accept(completed));
        });
    }

    public void importLatestHealthData(File directory, Consumer<HealthBackupResult> callback) {
        databaseExecutor.execute(() -> {
            HealthBackupResult result;
            try {
                File file = HealthBackupCodec.newest(directory);
                if (file == null) {
                    result = HealthBackupResult.error("No timestamped backup found");
                } else {
                    List<HealthSampleEntity> imported = HealthBackupCodec.read(file);
                    long[] insertedIds = dao.insertSamples(imported);
                    int inserted = 0;
                    for (long id : insertedIds) if (id != -1L) inserted++;
                    result = HealthBackupResult.success(
                            "Imported " + inserted + " new of " + imported.size() + " samples",
                            file.getName(), inserted);
                    refreshStorage();
                }
            } catch (Exception failure) {
                Log.e(TAG, "Health import failed", failure);
                result = HealthBackupResult.error("Import failed: " + safeMessage(failure));
            }
            HealthBackupResult completed = result;
            main.post(() -> callback.accept(completed));
        });
    }

    /** Enables sleep history import. Sleep detection on the ring itself is always automatic. */
    public void setSleepSyncEnabled(boolean enabled) {
        worker.post(() -> {
            if (sleepSyncEnabled == enabled) return;
            boolean preserveOverdueDeadline = periodicSyncEnabled
                    && nextPeriodicSyncAt > 0L
                    && nextPeriodicSyncAt <= System.currentTimeMillis();
            sleepSyncEnabled = enabled;
            preferences.edit().putBoolean(PREF_SLEEP_SYNC_ENABLED, enabled).apply();
            if (preserveOverdueDeadline) publishSnapshot();
            else scheduleNextPeriodicSyncFromMetrics(System.currentTimeMillis());
        });
    }

    public void refreshData() { refreshStorage(); }

    /** Reconnects the existing process-wide transport without creating a second GATT owner. */
    public void reconnect() {
        worker.post(() -> {
            stopScan();
            if (gatt != null) {
                try { gatt.disconnect(); } catch (RuntimeException ignored) {}
            }
            closeGatt();
            gattConnected = false;
            notificationsReady = false;
            setState(ConnectionState.DISCONNECTED_RETRYING, "Manual reconnect");
            connectIfPossible();
        });
    }

    /** Requests a fresh battery frame through the same serialized R08 command queue. */
    public void requestBatteryRefresh() {
        worker.post(() -> {
            if (connectionState != ConnectionState.READY || !notificationsReady) return;
            enqueueControl(ControlRequest.response("battery refresh", 0x03,
                    ControlProtocol.readBattery(), frame -> {
                        batteryPercent = ControlProtocol.parseBatteryPercent(frame);
                        batteryCharging = ControlProtocol.parseBatteryCharging(frame);
                        publishSnapshot();
                        return true;
                    }, null, true));
        });
    }

    /** Removes the bonded R08 after closing this backend's GATT connection. */
    public void forgetDevice(Consumer<Boolean> result) {
        worker.post(() -> {
            started = false;
            stopScan();
            if (gatt != null) {
                try { gatt.disconnect(); } catch (RuntimeException ignored) {}
            }
            closeGatt();
            BluetoothDevice bondedDevice = findSavedOrBonded();
            boolean submitted = false;
            if (bondedDevice != null) {
                try {
                    Method removeBond = BluetoothDevice.class.getMethod("removeBond");
                    submitted = Boolean.TRUE.equals(removeBond.invoke(bondedDevice));
                } catch (ReflectiveOperationException error) {
                    diagnostic("removeBond failed " + error.getClass().getSimpleName());
                }
            }
            if (submitted) {
                preferences.edit().remove("ring_address").remove("ring_name").apply();
                ringAddress = "";
                ringName = "R08";
                bonded = false;
                setState(ConnectionState.UNPAIRED, "Forget requested");
            }
            if (result != null) result.accept(submitted);
        });
    }

    public static int[] supportedHeartRateIntervals(int reportedMinimum) {
        return ControlProtocol.supportedHeartRateIntervals(reportedMinimum);
    }

    /** Hosts may preserve their existing ring setup without adding it to the Health library. */
    protected List<HostBootstrapCommand> additionalBootstrapCommands() {
        return List.of();
    }

    /** Lets a host serialize its already-supported R08 setup commands on this GATT owner. */
    protected final void submitHostCommands(List<HostBootstrapCommand> commands) {
        if (commands == null || commands.isEmpty()) return;
        worker.post(() -> {
            if (!started || !gattConnected || !notificationsReady) return;
            for (HostBootstrapCommand command : commands) {
                enqueueControl(ControlRequest.response(command.label, command.responseOpcode,
                        command.frame, frame -> true, null, true));
            }
        });
    }

    private void initializeBluetooth() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            setState(ConnectionState.FATAL_ERROR, "Bluetooth adapter missing");
            return;
        }
        registerBondReceiver();
        connectIfPossible();
    }

    private void connectIfPossible() {
        if (!started || adapter == null) return;
        if (!hasConnectPermission() || !hasScanPermission()) {
            setState(ConnectionState.UNPAIRED, "Bluetooth permission required");
            return;
        }
        if (gattConnected || scanning || connectionState == ConnectionState.CONNECTING_GATT
                || connectionState == ConnectionState.BONDING) return;
        BluetoothDevice saved = findSavedOrBonded();
        if (saved != null) {
            rememberTarget(saved);
            bondOrConnect(saved);
        } else {
            startScan();
        }
    }

    private void registerBondReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(bondReceiver, filter);
        receiverRegistered = true;
    }

    private BluetoothDevice findSavedOrBonded() {
        if (!ringAddress.isEmpty()) {
            try {
                BluetoothDevice saved = adapter.getRemoteDevice(ringAddress);
                if (saved.getBondState() == BluetoothDevice.BOND_BONDED) return saved;
            } catch (IllegalArgumentException ignored) {
                preferences.edit().remove("ring_address").apply();
                ringAddress = "";
            }
        }
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        if (devices != null) {
            for (BluetoothDevice device : devices) if (isR08(device)) return device;
        }
        return null;
    }

    private void startScan() {
        if (!hasScanPermission() || scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setState(ConnectionState.FATAL_ERROR, "BLE scanner missing");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        setState(ConnectionState.SCANNING, ringAddress.isEmpty() ? "searching for R08" : "searching for saved R08");
        scanner.startScan(null, settings, scanCallback);
        worker.removeCallbacks(scanTimeout);
        worker.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        worker.removeCallbacks(scanTimeout);
        if (scanning && scanner != null && hasScanPermission()) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        }
        scanning = false;
    }

    private void bondOrConnect(BluetoothDevice device) {
        if (!hasConnectPermission()) return;
        bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
        if (!bonded) {
            setState(ConnectionState.BONDING, "waiting for Android bond");
            if (!device.createBond()) {
                diagnostic("createBond returned false");
                startScan();
            }
        } else {
            connectGatt(device);
        }
    }

    private void onBondState(BluetoothDevice device, int state) {
        diagnostic("bond state=" + state);
        bonded = state == BluetoothDevice.BOND_BONDED;
        publishSnapshot();
        if (bonded) {
            rememberTarget(device);
            connectGatt(device);
        } else if (state == BluetoothDevice.BOND_NONE) {
            setState(ConnectionState.UNPAIRED, "bond removed or failed");
            startScan();
        }
    }

    private void connectGatt(BluetoothDevice device) {
        if (!started || !hasConnectPermission()) return;
        stopScan();
        closeGatt();
        targetDevice = device;
        setState(ConnectionState.CONNECTING_GATT, safeName(device));
        diagnostic("connectGatt " + device.getAddress());
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) scheduleReconnect("connectGatt returned null");
    }

    private void handleConnectionState(BluetoothGatt callbackGatt, int status, int newState) {
        diagnostic("connection state=" + newState + " status=" + status);
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            if (callbackGatt != gatt) {
                callbackGatt.close();
                return;
            }
            gattConnected = true;
            reconnectAttempt = 0;
            setState(ConnectionState.DISCOVERING_SERVICES, "GATT connected");
            if (!callbackGatt.discoverServices()) disconnectAndRetry("discoverServices rejected");
            return;
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            if (callbackGatt == gatt) handleDisconnected("status=" + status);
            else callbackGatt.close();
        }
    }

    private void handleServicesDiscovered(BluetoothGatt callbackGatt, int status) {
        if (callbackGatt != gatt) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            disconnectAndRetry("service discovery status=" + status);
            return;
        }
        BluetoothGattService controlService = callbackGatt.getService(CONTROL_SERVICE);
        BluetoothGattService largeService = callbackGatt.getService(LARGE_SERVICE);
        if (controlService == null || largeService == null) {
            setState(ConnectionState.FATAL_ERROR, "R08 services missing");
            closeGatt();
            return;
        }
        controlWrite = controlService.getCharacteristic(CONTROL_WRITE);
        controlNotify = controlService.getCharacteristic(CONTROL_NOTIFY);
        largeWrite = largeService.getCharacteristic(LARGE_WRITE);
        largeNotify = largeService.getCharacteristic(LARGE_NOTIFY);
        if (controlWrite == null || controlNotify == null || largeWrite == null || largeNotify == null) {
            setState(ConnectionState.FATAL_ERROR, "R08 characteristics missing");
            closeGatt();
            return;
        }
        setState(ConnectionState.ENABLING_NOTIFICATIONS, "requesting MTU");
        worker.postDelayed(beginNotificationsFallback, 2_500L);
        if (!callbackGatt.requestMtu(247)) {
            worker.removeCallbacks(beginNotificationsFallback);
            beginNotifications();
        }
    }

    private void beginNotifications() {
        if (gatt == null || !gattConnected || notificationDescriptorsRemaining > 0) return;
        if (controlNotify == null || largeNotify == null) {
            diagnostic("notify setup deferred until characteristics are available");
            return;
        }
        notificationDescriptorsRemaining = 2;
        enqueueNotification(controlNotify, "control notify");
        enqueueNotification(largeNotify, "large notify");
    }

    private void enqueueNotification(BluetoothGattCharacteristic characteristic, String label) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            disconnectAndRetry(label + " local enable rejected");
            return;
        }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            disconnectAndRetry(label + " CCCD missing");
            return;
        }
        gattQueue.add(GattOperation.descriptor(label, descriptor,
                () -> {
                    notificationDescriptorsRemaining--;
                    if (notificationDescriptorsRemaining == 0) {
                        notificationsReady = true;
                        beginBootstrap();
                    }
                }, () -> disconnectAndRetry(label + " failed")));
    }

    private void beginBootstrap() {
        setState(ConnectionState.INITIALIZING, "QRing bootstrap");
        capabilities = Capabilities.VERIFIED_R08;
        advertisedCapabilities = Capabilities.UNKNOWN;
        heartRateSettings = null;
        spo2Settings = null;
        temperatureSettings = null;
        autoSettingsUpdating = false;
        autoSettingsStatus = "Reading from ring";
        enqueueControl(ControlRequest.response("set time", 0x01,
                ControlProtocol.setTime(ZonedDateTime.now()), frame -> {
                    Capabilities timeSupport = ControlProtocol.parseTimeSupport(frame);
                    capabilities = capabilities.mergeSupported(timeSupport);
                    diagnostic("time support stress=" + timeSupport.manualStress
                            + " hrv=" + timeSupport.manualHrv
                            + " newSleep=" + timeSupport.newSleepProtocol);
                    publishSnapshot();
                    return true;
                }, null, true));
        enqueueControl(ControlRequest.response("capabilities", 0x3C,
                ControlProtocol.readCapabilities(), frame -> {
                    // Firmware capability masks are not stable enough to treat an absent bit as
                    // proof that a verified R08 command is unsupported. They may only add support.
                    advertisedCapabilities = ControlProtocol.parseCapabilities(frame);
                    capabilities = capabilities.mergeSupported(advertisedCapabilities);
                    publishSnapshot();
                    return true;
                }, null, true));
        enqueueControl(ControlRequest.response("heart settings", 0x16,
                ControlProtocol.readHeartSettings(), frame -> {
                    heartRateSettings = ControlProtocol.parseHeartRateSettings(frame);
                    diagnostic("auto settings HEART_RATE enabled=" + heartRateSettings.enabled()
                            + " interval=" + heartRateSettings.intervalMinutes());
                    publishSnapshot();
                    return true;
                }, null, true));
        enqueueControl(ControlRequest.response("SpO2 settings", 0x2C,
                ControlProtocol.readSpo2Settings(), frame -> {
                    spo2Settings = ControlProtocol.parseSpo2Settings(frame);
                    diagnostic("auto settings SPO2 enabled=" + spo2Settings.enabled()
                            + " firmwareInterval=" + spo2Settings.firmwareIntervalMinutes());
                    publishSnapshot();
                    return true;
                }, null, true));
        enqueueControl(ControlRequest.response("temperature settings", 0x3A,
                ControlProtocol.readTemperatureSettings(), frame -> {
                    temperatureSettings = ControlProtocol.parseTemperatureSettings(frame);
                    diagnostic("auto settings TEMPERATURE enabled=" + temperatureSettings.enabled()
                            + " interval=" + temperatureSettings.intervalMinutes());
                    autoSettingsStatus = "Loaded from ring";
                    publishSnapshot();
                    return true;
                }, null, true));
        enqueueControl(ControlRequest.response("battery", 0x03,
                ControlProtocol.readBattery(), frame -> {
                    batteryPercent = ControlProtocol.parseBatteryPercent(frame);
                    batteryCharging = ControlProtocol.parseBatteryCharging(frame);
                    return true;
                }, this::enqueueAdditionalBootstrapCommands, true));
    }

    private void enqueueAdditionalBootstrapCommands() {
        List<HostBootstrapCommand> commands = additionalBootstrapCommands();
        if (commands == null || commands.isEmpty()) {
            markReady();
            return;
        }
        for (int index = 0; index < commands.size(); index++) {
            HostBootstrapCommand command = commands.get(index);
            Runnable finished = index == commands.size() - 1 ? this::markReady : null;
            enqueueControl(ControlRequest.response(command.label, command.responseOpcode,
                    command.frame, frame -> true, finished, true));
        }
    }

    private void markReady() {
        reconnectAttempt = 0;
        setState(ConnectionState.READY, "bootstrap complete");
        refreshStorage();
    }

    private void enqueueControl(ControlRequest request) {
        controlRequests.add(request);
        drainControlQueue();
    }

    private void drainControlQueue() {
        if (activeControl != null || !notificationsReady || !gattConnected) return;
        if (controlRequests.isEmpty()) {
            tryStartPendingManualSync();
            tryStartPendingPeriodicSync();
            return;
        }
        activeControl = controlRequests.remove();
        ControlRequest request = activeControl;
        diagnostic("control tx " + request.label + " " + ControlProtocol.hex(request.frame));
        gattQueue.add(GattOperation.characteristic(request.label, controlWrite, request.frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                () -> {
                    if (activeControl != request) return;
                    if (request.expectedOpcode < 0) {
                        finishControl(request);
                    } else {
                        worker.removeCallbacks(controlTimeout);
                        worker.postDelayed(controlTimeout, request.timeoutMs);
                    }
                }, () -> {
                    if (activeControl == request) {
                        activeControl = null;
                        if (request.onTimeout != null) request.onTimeout.run();
                        drainControlQueue();
                    }
                }));
    }

    private void finishControl(ControlRequest request) {
        if (activeControl != request) return;
        worker.removeCallbacks(controlTimeout);
        activeControl = null;
        if (request.onFinished != null) request.onFinished.run();
        drainControlQueue();
    }

    private void dispatchNotification(UUID characteristic, byte[] value) {
        if (value == null) return;
        worker.post(() -> {
            if (CONTROL_NOTIFY.equals(characteristic)) handleControlNotification(value);
            else if (LARGE_NOTIFY.equals(characteristic)) handleLargeNotification(value);
        });
    }

    private void handleControlNotification(byte[] frame) {
        if (!ControlProtocol.isValid(frame)) {
            diagnostic("invalid control checksum " + ControlProtocol.hex(frame));
            return;
        }
        int opcode = ControlProtocol.opcode(frame);
        diagnostic("control rx " + hexByte(opcode) + " " + ControlProtocol.hex(frame));
        if (ControlProtocol.hasErrorBit(frame)) {
            diagnostic("control error bit opcode=" + hexByte(opcode));
        }
        if (syncing && historyKind == HistoryKind.LEGACY_HEART && opcode == 0x15) {
            handleLegacyHeartPacket(frame);
            return;
        }
        ControlRequest request = activeControl;
        if (request != null && request.expectedOpcode == opcode) {
            try {
                if (request.responseHandler == null || request.responseHandler.onResponse(frame)) {
                    finishControl(request);
                }
            } catch (RuntimeException error) {
                diagnostic("control parse error " + error.getMessage());
                finishControl(request);
            }
        } else if (opcode == 0x03) {
            try {
                batteryPercent = ControlProtocol.parseBatteryPercent(frame);
                batteryCharging = ControlProtocol.parseBatteryCharging(frame);
                publishSnapshot();
            }
            catch (IllegalArgumentException error) { diagnostic(error.getMessage()); }
        }
    }

    private void updateAutoMeasurement(HealthMetric metric, boolean enabled, int heartInterval) {
        if (!metric.hasAutoSettings()) {
            autoSettingsStatus = metric.title + ": no QRing auto setting";
            publishSnapshot();
            return;
        }
        if (connectionState != ConnectionState.READY || !gattConnected || !notificationsReady
                || syncing || activeMeasurement != null || activeControl != null
                || !controlRequests.isEmpty() || autoSettingsUpdating) {
            autoSettingsStatus = "Unavailable while busy or disconnected";
            publishSnapshot();
            return;
        }
        byte[] writeFrame = null;
        int opcode = -1;
        switch (metric) {
            case HEART_RATE -> {
                if (heartRateSettings == null
                        || !contains(ControlProtocol.supportedHeartRateIntervals(
                                heartRateSettings.startInterval()), heartInterval)) {
                    autoSettingsStatus = "Invalid or unavailable QRing heart interval";
                    publishSnapshot();
                    return;
                }
                opcode = 0x16;
                writeFrame = ControlProtocol.writeHeartRateSettings(
                        heartRateSettings, enabled, heartInterval);
            }
            case SPO2 -> {
                if (spo2Settings == null) {
                    autoSettingsStatus = "SpO2 settings not loaded";
                    publishSnapshot();
                    return;
                }
                opcode = 0x2C;
                writeFrame = ControlProtocol.writeSpo2Settings(enabled);
            }
            case TEMPERATURE -> {
                if (temperatureSettings == null) {
                    autoSettingsStatus = "Temperature settings not loaded";
                    publishSnapshot();
                    return;
                }
                opcode = 0x3A;
                writeFrame = ControlProtocol.writeTemperatureSettings(temperatureSettings, enabled);
            }
            case STRESS, HRV -> {
                autoSettingsStatus = metric.title + ": no QRing auto setting";
                publishSnapshot();
                return;
            }
        }
        if (writeFrame == null || opcode < 0) return;
        autoSettingsUpdating = true;
        autoSettingsStatus = "Writing " + metric.title;
        publishSnapshot();
        enqueueControl(ControlRequest.response("write auto " + metric.name(), opcode, writeFrame,
                frame -> true,
                () -> verifyAutoMeasurement(metric, enabled, heartInterval),
                () -> failAutoSettings("write timeout " + metric.name()), CONTROL_TIMEOUT_MS));
    }

    private void verifyAutoMeasurement(HealthMetric metric, boolean expectedEnabled,
                                       int expectedHeartInterval) {
        int opcode = switch (metric) {
            case HEART_RATE -> 0x16;
            case SPO2 -> 0x2C;
            case STRESS, HRV -> throw new IllegalArgumentException("Metric has no auto setting");
            case TEMPERATURE -> 0x3A;
        };
        byte[] readFrame = switch (metric) {
            case HEART_RATE -> ControlProtocol.readHeartSettings();
            case SPO2 -> ControlProtocol.readSpo2Settings();
            case STRESS, HRV -> throw new IllegalArgumentException("Metric has no auto setting");
            case TEMPERATURE -> ControlProtocol.readTemperatureSettings();
        };
        autoSettingsStatus = "Verifying " + metric.title;
        publishSnapshot();
        enqueueControl(ControlRequest.response("verify auto " + metric.name(), opcode, readFrame,
                frame -> {
                    parseAutoSettings(metric, frame);
                    return true;
                }, () -> {
                    boolean matches = switch (metric) {
                        case HEART_RATE -> heartRateSettings != null
                                && heartRateSettings.enabled() == expectedEnabled
                                && heartRateSettings.intervalMinutes() == expectedHeartInterval;
                        case SPO2 -> spo2Settings != null
                                && spo2Settings.enabled() == expectedEnabled;
                        case STRESS, HRV -> false;
                        case TEMPERATURE -> temperatureSettings != null
                                && temperatureSettings.enabled() == expectedEnabled;
                    };
                    autoSettingsUpdating = false;
                    autoSettingsStatus = matches ? metric.title + ": saved and verified"
                            : metric.title + ": read-back mismatch";
                    diagnostic("auto settings verify " + metric + " result=" + matches);
                    publishSnapshot();
                }, () -> failAutoSettings("verification timeout " + metric.name()),
                CONTROL_TIMEOUT_MS));
    }

    private void parseAutoSettings(HealthMetric metric, byte[] frame) {
        switch (metric) {
            case HEART_RATE -> heartRateSettings = ControlProtocol.parseHeartRateSettings(frame);
            case SPO2 -> spo2Settings = ControlProtocol.parseSpo2Settings(frame);
            case STRESS, HRV -> throw new IllegalArgumentException("Metric has no auto setting");
            case TEMPERATURE -> temperatureSettings = ControlProtocol.parseTemperatureSettings(frame);
        }
    }

    private void failAutoSettings(String reason) {
        autoSettingsUpdating = false;
        autoSettingsStatus = "Failed: " + reason;
        diagnostic("auto settings " + reason);
        publishSnapshot();
    }

    private static boolean contains(int[] values, int expected) {
        for (int value : values) if (value == expected) return true;
        return false;
    }

    private void startMeasurement(HealthMetric metric) {
        if (connectionState != ConnectionState.READY || !gattConnected || !notificationsReady) {
            measurementStatus = "Disconnected";
            publishSnapshot();
            return;
        }
        if (syncing || activeMeasurement != null || activeControl != null || !controlRequests.isEmpty()) {
            measurementStatus = "Busy";
            publishSnapshot();
            return;
        }
        if (!capabilities.supportsManual(metric)) {
            measurementStatus = "Unsupported";
            publishSnapshot();
            return;
        }
        activeMeasurement = metric;
        measurementLastRaw = 0;
        measurementFinalizeAttempts = 0;
        measurementStatus = "Measuring";
        long timeoutMs = metric.manualMeasurementTimeoutMs();
        measurementStartedAtEpochMs = System.currentTimeMillis();
        measurementDeadlineAtEpochMs = measurementStartedAtEpochMs + timeoutMs;
        publishSnapshot();
        worker.removeCallbacks(measurementTimeout);
        worker.postDelayed(measurementTimeout, timeoutMs);
        enqueueControl(ControlRequest.response("measure " + metric.name(), 0x69,
                ControlProtocol.startMeasurement(metric), frame -> onMeasurementFrame(metric, frame),
                null, false, timeoutMs));
    }

    private boolean onMeasurementFrame(HealthMetric expected, byte[] frame) {
        ControlProtocol.MeasurementReading reading = ControlProtocol.parseMeasurement(frame);
        if (reading.metric() != expected) return false;
        if (reading.errorCode() == 1) {
            finishMeasurement("Not worn", false, true);
            return true;
        }
        if (reading.errorCode() != 0) {
            finishMeasurement("Error " + reading.errorCode(), false, true);
            return true;
        }
        measurementLastRaw = reading.rawValue();
        if (!reading.valid()) {
            measurementStatus = expected == HealthMetric.TEMPERATURE ? "Warming up" : "Measuring";
            publishSnapshot();
            return false;
        }
        persistMeasurement(reading, true);
        return true;
    }

    private void finishTimedOutMeasurement() {
        HealthMetric metric = activeMeasurement;
        if (metric == null) return;
        if (!shouldFinalizeTimedOutMeasurement(metric, gattConnected, notificationsReady)) {
            finishMeasurement("Timeout", false, true);
            return;
        }
        worker.removeCallbacks(measurementTimeout);
        worker.removeCallbacks(controlTimeout);
        if (activeControl != null && activeControl.expectedOpcode == 0x69) activeControl = null;
        measurementStatus = "Finalizing";
        publishSnapshot();
        requestFinalMeasurement(metric);
    }

    static boolean shouldFinalizeTimedOutMeasurement(HealthMetric metric, boolean connected,
                                                      boolean notificationsReady) {
        return metric != null && connected && notificationsReady;
    }

    private void requestFinalMeasurement(HealthMetric metric) {
        if (activeMeasurement != metric) return;
        measurementFinalizeAttempts++;
        enqueueControl(ControlRequest.response("finalize " + metric.name(), 0x6A,
                ControlProtocol.stopMeasurement(metric, measurementLastRaw),
                frame -> onStoppedMeasurementFrame(metric, frame), null,
                () -> {
                    if (activeMeasurement != metric) return;
                    if (measurementFinalizeAttempts < FINAL_MEASUREMENT_ATTEMPTS
                            && gattConnected && notificationsReady) {
                        diagnostic("retry final measurement " + metric
                                + " attempt=" + (measurementFinalizeAttempts + 1));
                        worker.postDelayed(() -> requestFinalMeasurement(metric), 250L);
                    } else {
                        finishMeasurement("Timeout", false, false);
                    }
                }, FINAL_MEASUREMENT_TIMEOUT_MS));
    }

    private boolean onStoppedMeasurementFrame(HealthMetric expected, byte[] frame) {
        final ControlProtocol.MeasurementReading reading;
        try {
            reading = ControlProtocol.parseStoppedMeasurement(frame);
        } catch (IllegalArgumentException error) {
            diagnostic("final measurement parse error " + error.getMessage());
            finishMeasurement("Timeout", false, false);
            return true;
        }
        if (reading.metric() != expected) return false;
        if (reading.errorCode() != 0 || !reading.valid()) {
            finishMeasurement(reading.errorCode() == 0 ? "Timeout" : "Error " + reading.errorCode(),
                    false, false);
            return true;
        }
        measurementLastRaw = reading.rawValue();
        persistMeasurement(reading, false);
        return true;
    }

    private void persistMeasurement(ControlProtocol.MeasurementReading reading, boolean sendStop) {
        HealthMetric metric = reading.metric();
        long now = System.currentTimeMillis();
        HealthSampleEntity sample = HealthSampleEntity.manual(ringId(), metric.name(), now,
                reading.value(), reading.rawValue());
        databaseExecutor.execute(() -> {
            dao.insertSample(sample);
            worker.post(() -> {
                latest.put(metric, sample);
                diagnostic("measurement saved " + metric + " value=" + reading.value());
                finishMeasurement("Success", true, sendStop);
                refreshStorage();
            });
        });
    }

    private void finishMeasurement(String status, boolean success, boolean sendStop) {
        HealthMetric metric = activeMeasurement;
        if (metric == null) return;
        worker.removeCallbacks(measurementTimeout);
        worker.removeCallbacks(controlTimeout);
        if (activeControl != null && activeControl.expectedOpcode == 0x69) activeControl = null;
        activeMeasurement = null;
        measurementStatus = status;
        measurementStartedAtEpochMs = 0L;
        measurementDeadlineAtEpochMs = 0L;
        publishSnapshot();
        if (sendStop && gattConnected && notificationsReady) {
            enqueueControl(ControlRequest.fireAndForget("stop " + metric.name(),
                    ControlProtocol.stopMeasurement(metric, measurementLastRaw)));
        } else {
            drainControlQueue();
        }
        if (!success) diagnostic("measurement " + metric + " ended: " + status);
    }

    private void requestManualSync() {
        if (syncing && activeSyncTrigger == SyncTrigger.MANUAL) {
            syncStatus = "Manual: already running";
            publishSnapshot();
            return;
        }
        manualSyncQueue.enqueue();
        syncStatus = manualSyncQueuedStatus();
        diagnostic(syncStatus.toLowerCase(Locale.US));
        publishSnapshot();
        if (connectionState != ConnectionState.READY || !gattConnected || !notificationsReady) {
            connectIfPossible();
        }
        tryStartPendingManualSync();
    }

    private String manualSyncQueuedStatus() {
        if (connectionState != ConnectionState.READY || !gattConnected || !notificationsReady) {
            return "Manual: queued until ring reconnects";
        }
        if (activeMeasurement != null) return "Manual: queued after measurement";
        if (syncing) return "Manual: queued after current sync";
        return "Manual: queued until ring is idle";
    }

    private void tryStartPendingManualSync() {
        boolean transportIdle = connectionState == ConnectionState.READY
                && gattConnected && notificationsReady && !syncing
                && activeMeasurement == null && activeControl == null
                && controlRequests.isEmpty() && !autoSettingsUpdating;
        if (!manualSyncQueue.pollIfIdle(transportIdle)) return;
        diagnostic("starting queued manual sync");
        startHistorySync(SyncTrigger.MANUAL);
    }

    private void startHistorySync(SyncTrigger trigger) {
        if (connectionState != ConnectionState.READY || activeMeasurement != null || syncing
                || activeControl != null || !controlRequests.isEmpty()) {
            if (trigger == SyncTrigger.PERIODIC) {
                queuePeriodicSync("waiting for active ring operation");
            } else {
                syncStatus = "Manual: unavailable while busy or disconnected";
            }
            publishSnapshot();
            return;
        }
        syncMetrics.clear();
        long now = System.currentTimeMillis();
        for (HealthMetric metric : HealthMetric.values()) {
            if (!capabilities.supportsHistory(metric)) continue;
            if (trigger == SyncTrigger.MANUAL || shouldPeriodicallySync(metric, now)) {
                syncMetrics.add(metric);
            }
        }
        sleepSyncQueued = sleepSyncEnabled && capabilities.newSleepProtocol
                && (trigger == SyncTrigger.MANUAL || shouldPeriodicallySyncSleep(now));
        // Steps are a passive counter and require no auto-measurement switch. Read today's
        // lightweight total on every Health sync; retained history is fetched only as needed.
        stepSyncQueued = true;
        if (syncMetrics.isEmpty() && !stepSyncQueued && !sleepSyncQueued) {
            if (trigger == SyncTrigger.PERIODIC) {
                clearPendingPeriodicSync();
                boolean anyEnabled = hasEnabledPeriodicMetric();
                periodicSyncStatus = anyEnabled ? "Up to date" : "No enabled auto metrics";
                syncStatus = "Auto: " + periodicSyncStatus;
                diagnostic("periodic sync skipped: " + periodicSyncStatus);
                scheduleNextPeriodicSyncFromMetrics(now);
            } else {
                syncStatus = "Manual: unsupported";
            }
            publishSnapshot();
            return;
        }
        if (trigger == SyncTrigger.PERIODIC) clearPendingPeriodicSync();
        syncing = true;
        activeSyncTrigger = trigger;
        syncCompleted.clear();
        syncFailed.clear();
        sleepCompleted = false;
        sleepFailed = false;
        sleepSyncActive = false;
        stepCompleted = false;
        stepFailed = false;
        stepSyncActive = false;
        stepPersisting = false;
        syncStatus = trigger == SyncTrigger.PERIODIC ? "Auto: starting" : "Manual: starting";
        syncRun = new SyncRunEntity();
        syncRun.startedAtEpochMs = System.currentTimeMillis();
        syncRun.requestedMetrics = joinSyncTargets(syncMetrics, stepSyncQueued, sleepSyncQueued);
        diagnostic("history sync " + trigger + " metrics=" + syncRun.requestedMetrics);
        SyncRunEntity startedRun = syncRun;
        publishSnapshot();
        databaseExecutor.execute(() -> {
            startedRun.id = dao.insertSyncRun(startedRun);
            worker.post(() -> {
                if (syncing && syncRun == startedRun) syncNextMetric();
            });
        });
    }

    private boolean shouldPeriodicallySync(HealthMetric metric, long nowEpochMs) {
        AutoMeasurementSettings.MetricSetting setting = autoSettingFor(metric);
        return PeriodicSyncPolicy.shouldSyncMetric(setting.loaded(), setting.enabled(),
                capabilities.supportsHistory(metric), lastHistorySyncAt.getOrDefault(metric, 0L),
                nowEpochMs, periodicSyncIntervalMinutes);
    }

    private boolean hasEnabledPeriodicMetric() {
        for (HealthMetric metric : HealthMetric.values()) {
            AutoMeasurementSettings.MetricSetting setting = autoSettingFor(metric);
            if (capabilities.supportsHistory(metric) && setting.loaded() && setting.enabled()) {
                return true;
            }
        }
        return true;
    }

    private boolean shouldPeriodicallySyncSleep(long nowEpochMs) {
        long intervalMs = Math.max(periodicSyncIntervalMinutes, SLEEP_MIN_SYNC_INTERVAL_MINUTES)
                * 60_000L;
        return lastSleepSyncAt <= 0L || nowEpochMs - lastSleepSyncAt >= intervalMs;
    }

    private AutoMeasurementSettings.MetricSetting autoSettingFor(HealthMetric metric) {
        return switch (metric) {
            case HEART_RATE -> heartRateSettings == null ? AutoMeasurementSettings.UNKNOWN
                    : new AutoMeasurementSettings.MetricSetting(true,
                            heartRateSettings.enabled(), heartRateSettings.intervalMinutes(),
                            heartRateSettings.startInterval());
            case SPO2 -> spo2Settings == null ? AutoMeasurementSettings.UNKNOWN
                    : new AutoMeasurementSettings.MetricSetting(true, spo2Settings.enabled(),
                            spo2Settings.firmwareIntervalMinutes(), 0);
            case TEMPERATURE -> temperatureSettings == null ? AutoMeasurementSettings.UNKNOWN
                    : new AutoMeasurementSettings.MetricSetting(true,
                            temperatureSettings.enabled(), temperatureSettings.intervalMinutes(), 0);
            case STRESS, HRV -> AutoMeasurementSettings.UNKNOWN;
        };
    }

    private void syncNextMetric() {
        worker.removeCallbacks(historyTimeout);
        worker.removeCallbacks(sleepCompanionTimeout);
        if (!syncing) return;
        historyMetric = syncMetrics.poll();
        if (historyMetric == null) {
            if (stepSyncQueued && !stepCompleted && !stepFailed) startStepSync();
            else if (sleepSyncQueued && !sleepCompleted && !sleepFailed) startSleepSync();
            else finishHistorySync();
            return;
        }
        historyKind = selectHistoryKind(historyMetric);
        historyAction = switch (historyKind) {
            case INTERVAL -> historyMetric.historyAction;
            case LEGACY_HEART -> 0x15;
            case LEGACY_SPO2 -> LegacyHistoryProtocol.SPO2_ACTION;
            case LEGACY_TEMPERATURE -> LegacyHistoryProtocol.TEMPERATURE_ACTION;
        };
        historyPage = 0;
        historyFirstSampleIndex = 0;
        historyPersisting = false;
        historyPendingSamples.clear();
        requestHistoryPage();
    }

    private void requestHistoryPage() {
        if (!syncing || historyMetric == null) return;
        HealthMetric requestedMetric = historyMetric;
        HistoryKind requestedKind = historyKind;
        int requestedPage = historyPage;
        syncStatus = historyMetric.title + (historyKind == HistoryKind.INTERVAL
                ? ": page " + historyPage : ": QRing legacy");
        publishSnapshot();
        if (historyKind == HistoryKind.LEGACY_HEART) {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            legacyHeartAssembler = new LegacyHistoryProtocol.HeartAssembler(today, ZoneId.systemDefault());
            byte[] request = LegacyHistoryProtocol.heartRequest(today);
            diagnostic("history tx HEART_RATE legacy " + ControlProtocol.hex(request));
            gattQueue.add(GattOperation.characteristic("history HEART_RATE legacy", controlWrite,
                    request, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    () -> scheduleHistoryTimeout(requestedMetric, requestedKind, requestedPage),
                    () -> worker.post(() -> failHistoryMetric("write failed"))));
            return;
        }
        byte[] request = historyKind == HistoryKind.INTERVAL
                ? LargeDataProtocol.historyRequest(historyMetric, 0, historyPage)
                : LegacyHistoryProtocol.largeRequest(historyAction,
                        historyKind == HistoryKind.LEGACY_SPO2 ? 0xFF : 1);
        diagnostic("history tx " + historyMetric + " " + historyKind + " "
                + ControlProtocol.hex(request));
        List<byte[]> fragments = LargeDataProtocol.fragment(request, mtu);
        for (int index = 0; index < fragments.size(); index++) {
            boolean last = index == fragments.size() - 1;
            gattQueue.add(GattOperation.characteristic("history " + historyMetric + " fragment " + index,
                    largeWrite, fragments.get(index), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    last ? () -> scheduleHistoryTimeout(requestedMetric, requestedKind, requestedPage) : null,
                    () -> worker.post(() -> failHistoryMetric("write failed"))));
        }
    }

    private void scheduleHistoryTimeout(HealthMetric metric, HistoryKind kind, int page) {
        if (!syncing || historyMetric != metric || historyKind != kind || historyPage != page) return;
        worker.removeCallbacks(historyTimeout);
        worker.postDelayed(historyTimeout, HISTORY_TIMEOUT_MS);
    }

    private void handleLargeNotification(byte[] fragment) {
        diagnostic("large rx " + ControlProtocol.hex(fragment));
        List<byte[]> frames;
        try {
            frames = largeReassembler.feed(fragment);
        } catch (IllegalArgumentException error) {
            diagnostic("large-data error " + error.getMessage());
            failHistoryMetric(error.getMessage());
            return;
        }
        for (byte[] bytes : frames) {
            try {
                LargeDataProtocol.Frame frame = LargeDataProtocol.parse(bytes);
                if (sleepSyncActive) handleSleepFrame(frame);
                else handleHistoryFrame(frame);
            } catch (IllegalArgumentException error) {
                if (sleepSyncActive) failSleepSync(error.getMessage());
                else failHistoryMetric(error.getMessage());
            }
        }
    }

    private void handleHistoryFrame(LargeDataProtocol.Frame frame) {
        if (!syncing || historyMetric == null || historyPersisting) return;
        if (frame.action() != historyAction) {
            failHistoryMetric("unexpected action " + frame.action());
            return;
        }
        if (LargeDataProtocol.isEmptyStatus(frame)) {
            completeHistoryMetric(List.of(), 0);
            return;
        }
        if (historyKind == HistoryKind.LEGACY_SPO2 || historyKind == HistoryKind.LEGACY_TEMPERATURE) {
            LegacyHistoryProtocol.Decoded decoded = historyKind == HistoryKind.LEGACY_SPO2
                    ? LegacyHistoryProtocol.parseSpo2(frame, System.currentTimeMillis(), ZoneId.systemDefault())
                    : LegacyHistoryProtocol.parseTemperature(frame, System.currentTimeMillis(), ZoneId.systemDefault());
            if (historyKind == HistoryKind.LEGACY_TEMPERATURE) {
                historyPendingSamples.addAll(decoded.samples());
                // A legacy request for offset N returns day N down to current day as separate frames.
                if ((frame.payload()[0] & 0xFF) == 0) {
                    completeHistoryMetric(new ArrayList<>(historyPendingSamples), decoded.intervalMinutes());
                } else {
                    scheduleHistoryTimeout(historyMetric, historyKind, historyPage);
                }
            } else {
                completeHistoryMetric(decoded.samples(), decoded.intervalMinutes());
            }
            return;
        }
        LargeDataProtocol.Page page = LargeDataProtocol.parsePage(frame);
        if (page.metric() != historyMetric || page.dayIndex() != 0 || page.pageIndex() != historyPage) {
            failHistoryMetric("out-of-order page");
            return;
        }
        worker.removeCallbacks(historyTimeout);
        List<LargeDataProtocol.Sample> decoded = LargeDataProtocol.samples(page, historyFirstSampleIndex,
                System.currentTimeMillis(), ZoneId.systemDefault());
        persistHistorySamples(decoded, page.intervalMinutes(), () -> {
                historyFirstSampleIndex += page.values().size();
                if (page.pageCount() == 0 || page.pageIndex() == page.pageCount() - 1) {
                    markHistoryMetricSynced(historyMetric);
                    syncNextMetric();
                } else {
                    historyPage++;
                    requestHistoryPage();
                }
        });
    }

    private void handleLegacyHeartPacket(byte[] frame) {
        if (legacyHeartAssembler == null) {
            failHistoryMetric("legacy heart state missing");
            return;
        }
        if ((frame[1] & 0xFF) == 0xFF) {
            completeHistoryMetric(List.of(), 0);
            return;
        }
        try {
            boolean accepted = legacyHeartAssembler.feed(frame);
            if (accepted) scheduleHistoryTimeout(historyMetric, historyKind, historyPage);
            if (legacyHeartAssembler.complete()) {
                LegacyHistoryProtocol.Decoded decoded = legacyHeartAssembler.decoded(System.currentTimeMillis());
                completeHistoryMetric(decoded.samples(), decoded.intervalMinutes());
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            failHistoryMetric(error.getMessage());
        }
    }

    private HistoryKind selectHistoryKind(HealthMetric metric) {
        return switch (metric) {
            case HEART_RATE -> advertisedCapabilities.intervalHeartRate
                    ? HistoryKind.INTERVAL : HistoryKind.LEGACY_HEART;
            case SPO2 -> advertisedCapabilities.intervalSpo2
                    ? HistoryKind.INTERVAL : HistoryKind.LEGACY_SPO2;
            case STRESS, HRV -> throw new IllegalArgumentException("No history route for " + metric);
            case TEMPERATURE -> advertisedCapabilities.intervalTemperature
                    ? HistoryKind.INTERVAL : HistoryKind.LEGACY_TEMPERATURE;
        };
    }

    private void completeHistoryMetric(List<LargeDataProtocol.Sample> samples, int intervalMinutes) {
        if (!syncing || historyMetric == null || historyPersisting) return;
        HealthMetric completedMetric = historyMetric;
        historyPersisting = true;
        worker.removeCallbacks(historyTimeout);
        persistHistorySamples(samples, intervalMinutes, () -> {
            if (!syncing || historyMetric != completedMetric) return;
            markHistoryMetricSynced(completedMetric);
            historyPersisting = false;
            syncNextMetric();
        });
    }

    private void markHistoryMetricSynced(HealthMetric metric) {
        long completedAt = System.currentTimeMillis();
        syncCompleted.add(metric);
        lastHistorySyncAt.put(metric, completedAt);
        preferences.edit().putLong(metricSyncPreferenceKey(metric), completedAt).apply();
        diagnostic("history " + metric + " fresh at " + completedAt);
    }

    private void persistHistorySamples(List<LargeDataProtocol.Sample> samples, int intervalMinutes,
                                       Runnable afterPersisted) {
        LocalDate today = Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneId.systemDefault()).toLocalDate();
        List<HealthSampleEntity> entities = new ArrayList<>();
        for (LargeDataProtocol.Sample sample : samples) {
            LocalDate sampleDate = Instant.ofEpochMilli(sample.observedAtEpochMs())
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            int dayIndex = (int) Math.max(0, ChronoUnit.DAYS.between(sampleDate, today));
            entities.add(HealthSampleEntity.interval(ringId(), sample.metric().name(),
                    sample.observedAtEpochMs(), sample.value(), dayIndex, intervalMinutes));
        }
        databaseExecutor.execute(() -> {
            if (!entities.isEmpty()) dao.insertSamples(entities);
            worker.post(afterPersisted);
        });
    }

    private void failHistoryMetric(String reason) {
        worker.removeCallbacks(historyTimeout);
        if (!syncing || historyMetric == null) return;
        largeReassembler.reset();
        diagnostic("history " + historyMetric + " failed: " + reason);
        syncFailed.add(historyMetric);
        syncNextMetric();
    }

    private void startStepSync() {
        historyMetric = null;
        stepSyncActive = true;
        stepPersisting = false;
        stepHistoryDayOffset = 0;
        stepDetailAssembler = null;
        syncStatus = "Steps: today";
        publishSnapshot();
        enqueueControl(ControlRequest.response("steps today", StepProtocol.OPCODE_TODAY,
                StepProtocol.todayRequest(), frame -> {
                    StepProtocol.TodayTotal total;
                    try {
                        total = StepProtocol.parseToday(frame);
                    } catch (IllegalArgumentException invalid) {
                        worker.post(() -> failStepSync(invalid.getMessage()));
                        return true;
                    }
                    LocalDate today = LocalDate.now(ZoneId.systemDefault());
                    StepDayEntity entity = stepEntity(today, total.steps(), total.runningSteps(),
                            total.calories(), total.distance(), total.activitySeconds());
                    stepPersisting = true;
                    persistStepDay(entity, () -> {
                        if (!syncing || !stepSyncActive) return;
                        todaySteps = today.equals(LocalDate.now(ZoneId.systemDefault()))
                                ? total.steps() : 0;
                        stepPersisting = false;
                        boolean historyDue = activeSyncTrigger == SyncTrigger.MANUAL
                                || lastStepHistoryEpochDay != today.toEpochDay();
                        if (historyDue) {
                            stepHistoryDayOffset = 1;
                            requestStepHistoryDay();
                        } else {
                            completeStepSync(false);
                        }
                    });
                    return true;
                }, null, () -> failStepSync("today response timeout"), CONTROL_TIMEOUT_MS));
    }

    private void requestStepHistoryDay() {
        if (!syncing || !stepSyncActive) return;
        if (stepHistoryDayOffset > StepProtocol.QRING_HISTORY_DAYS) {
            completeStepSync(true);
            return;
        }
        int requestedDay = stepHistoryDayOffset;
        stepDetailAssembler = new StepProtocol.DetailAssembler();
        syncStatus = "Steps: day " + requestedDay + "/" + StepProtocol.QRING_HISTORY_DAYS;
        publishSnapshot();
        enqueueControl(ControlRequest.response("steps history day " + requestedDay,
                StepProtocol.OPCODE_DETAIL, StepProtocol.detailRequest(requestedDay),
                frame -> {
                    try {
                        return stepDetailAssembler.accept(frame);
                    } catch (IllegalArgumentException | IllegalStateException invalid) {
                        stepDetailAssembler = null;
                        worker.post(() -> failStepSync(invalid.getMessage()));
                        return true;
                    }
                }, () -> {
                    if (!syncing || !stepSyncActive || stepDetailAssembler == null) return;
                    StepProtocol.DetailAssembler completed = stepDetailAssembler;
                    stepDetailAssembler = null;
                    if (completed.isEmpty()) {
                        stepHistoryDayOffset++;
                        requestStepHistoryDay();
                        return;
                    }
                    StepProtocol.DetailTotal total = completed.result();
                    StepDayEntity entity = stepEntity(total.date(), total.steps(), 0,
                            total.calories(), total.distance(), 0);
                    stepPersisting = true;
                    persistStepDay(entity, () -> {
                        if (!syncing || !stepSyncActive) return;
                        stepPersisting = false;
                        stepHistoryDayOffset++;
                        requestStepHistoryDay();
                    });
                }, () -> failStepSync("history day " + requestedDay + " timeout"),
                CONTROL_TIMEOUT_MS));
    }

    private StepDayEntity stepEntity(LocalDate date, int steps, int runningSteps, int calories,
                                     int distance, int activitySeconds) {
        StepDayEntity entity = new StepDayEntity();
        entity.ringId = ringId();
        entity.localDate = date.toString();
        entity.steps = Math.max(0, steps);
        entity.runningSteps = Math.max(0, runningSteps);
        entity.calories = Math.max(0, calories);
        entity.distance = Math.max(0, distance);
        entity.activitySeconds = Math.max(0, activitySeconds);
        entity.updatedAtEpochMs = System.currentTimeMillis();
        return entity;
    }

    private void persistStepDay(StepDayEntity entity, Runnable afterPersisted) {
        databaseExecutor.execute(() -> {
            dao.upsertStepDay(entity);
            worker.post(afterPersisted);
        });
    }

    private void completeStepSync(boolean historyRefreshed) {
        if (!syncing || !stepSyncActive) return;
        long completedAt = System.currentTimeMillis();
        lastStepSyncAt = completedAt;
        if (historyRefreshed) {
            lastStepHistoryEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        }
        preferences.edit()
                .putLong(PREF_STEP_SYNC_LAST, completedAt)
                .putLong(PREF_STEP_HISTORY_DAY, lastStepHistoryEpochDay)
                .apply();
        stepCompleted = true;
        stepSyncActive = false;
        stepSyncQueued = false;
        stepPersisting = false;
        diagnostic("history STEPS fresh at " + completedAt);
        syncNextMetric();
    }

    private void failStepSync(String reason) {
        if (!syncing || !stepSyncActive) return;
        stepFailed = true;
        stepSyncActive = false;
        stepSyncQueued = false;
        stepPersisting = false;
        stepDetailAssembler = null;
        diagnostic("history STEPS failed: " + reason);
        syncNextMetric();
    }

    private void startSleepSync() {
        historyMetric = null;
        sleepSyncActive = true;
        sleepPersisting = false;
        sleepNightReceived = false;
        sleepNapReceived = false;
        sleepPendingSessions.clear();
        syncStatus = "Sleep: " + (activeSyncTrigger == SyncTrigger.MANUAL
                ? "all retained" : "today");
        publishSnapshot();
        byte[] request = SleepProtocol.request(activeSyncTrigger == SyncTrigger.MANUAL);
        diagnostic("history tx SLEEP new-protocol " + ControlProtocol.hex(request));
        List<byte[]> fragments = LargeDataProtocol.fragment(request, mtu);
        for (int index = 0; index < fragments.size(); index++) {
            boolean last = index == fragments.size() - 1;
            gattQueue.add(GattOperation.characteristic("history SLEEP fragment " + index,
                    largeWrite, fragments.get(index),
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    last ? this::scheduleSleepTimeout : null,
                    () -> worker.post(() -> failSleepSync("write failed"))));
        }
    }

    private void scheduleSleepTimeout() {
        if (!syncing || !sleepSyncActive) return;
        worker.removeCallbacks(historyTimeout);
        worker.postDelayed(historyTimeout, HISTORY_TIMEOUT_MS);
    }

    private void handleSleepFrame(LargeDataProtocol.Frame frame) {
        if (!syncing || !sleepSyncActive || sleepPersisting) return;
        if (!SleepProtocol.isSleepAction(frame.action())) {
            failSleepSync("unexpected action " + frame.action());
            return;
        }
        List<SleepProtocol.DecodedSession> decoded = SleepProtocol.parse(frame,
                System.currentTimeMillis(), ZoneId.systemDefault());
        sleepPendingSessions.addAll(decoded);
        if (frame.action() == SleepProtocol.ACTION_NIGHT) sleepNightReceived = true;
        if (frame.action() == SleepProtocol.ACTION_NAP) sleepNapReceived = true;
        worker.removeCallbacks(historyTimeout);
        worker.removeCallbacks(sleepCompanionTimeout);
        diagnostic("history SLEEP rx action=" + hexByte(frame.action())
                + " sessions=" + decoded.size());
        if (sleepNightReceived && sleepNapReceived) completeSleepSync();
        else worker.postDelayed(sleepCompanionTimeout, SLEEP_COMPANION_GRACE_MS);
    }

    private void completeSleepSync() {
        if (!syncing || !sleepSyncActive || sleepPersisting) return;
        sleepPersisting = true;
        worker.removeCallbacks(historyTimeout);
        worker.removeCallbacks(sleepCompanionTimeout);
        ArrayList<SleepSessionEntity> entities = new ArrayList<>(sleepPendingSessions.size());
        for (SleepProtocol.DecodedSession decoded : sleepPendingSessions) {
            entities.add(SleepSessionEntity.from(ringId(), decoded));
        }
        databaseExecutor.execute(() -> {
            if (!entities.isEmpty()) dao.insertSleepSessions(entities);
            worker.post(() -> {
                if (!syncing || !sleepSyncActive) return;
                lastSleepSyncAt = System.currentTimeMillis();
                preferences.edit().putLong(PREF_SLEEP_SYNC_LAST, lastSleepSyncAt).apply();
                sleepCompleted = true;
                sleepSyncActive = false;
                sleepSyncQueued = false;
                sleepPersisting = false;
                diagnostic("history SLEEP fresh at " + lastSleepSyncAt
                        + " sessions=" + entities.size());
                syncNextMetric();
            });
        });
    }

    private void failSleepSync(String reason) {
        worker.removeCallbacks(historyTimeout);
        worker.removeCallbacks(sleepCompanionTimeout);
        if (!syncing || !sleepSyncActive) return;
        largeReassembler.reset();
        sleepFailed = true;
        sleepSyncActive = false;
        sleepSyncQueued = false;
        sleepPersisting = false;
        diagnostic("history SLEEP failed: " + reason);
        syncNextMetric();
    }

    private void finishHistorySync() {
        SyncTrigger completedTrigger = activeSyncTrigger;
        syncing = false;
        boolean hadFailures = !syncFailed.isEmpty() || stepFailed || sleepFailed;
        String result = hadFailures ? "Partial: failed "
                + joinSyncTargets(syncFailed, stepFailed, sleepFailed) : "Success";
        syncStatus = (completedTrigger == SyncTrigger.PERIODIC ? "Auto: " : "Manual: ") + result;
        long completedAt = System.currentTimeMillis();
        if (completedTrigger == SyncTrigger.PERIODIC) {
            lastPeriodicSyncAt = completedAt;
            periodicSyncStatus = result;
            preferences.edit().putLong(PREF_PERIODIC_SYNC_LAST, completedAt).apply();
        }
        activeSyncTrigger = null;
        SyncRunEntity completedRun = syncRun;
        syncRun = null;
        if (completedRun != null) {
            completedRun.endedAtEpochMs = completedAt;
            completedRun.completedMetrics = joinSyncTargets(
                    syncCompleted, stepCompleted, sleepCompleted);
            completedRun.failedMetrics = joinSyncTargets(syncFailed, stepFailed, sleepFailed);
            completedRun.status = hadFailures ? "PARTIAL" : "SUCCESS";
            databaseExecutor.execute(() -> dao.updateSyncRun(completedRun));
        }
        publishSnapshot();
        refreshStorage();
        tryStartPendingManualSync();
        if (syncing) {
            return;
        } else if (periodicSyncQueue.isPending()) {
            tryStartPendingPeriodicSync();
        } else if (hadFailures && periodicSyncEnabled && hasFailedEnabledPeriodicMetric()) {
            schedulePeriodicRetry("failed metric");
        } else {
            scheduleNextPeriodicSyncFromMetrics(completedAt);
        }
    }

    private boolean hasFailedEnabledPeriodicMetric() {
        for (HealthMetric metric : syncFailed) {
            AutoMeasurementSettings.MetricSetting setting = autoSettingFor(metric);
            if (capabilities.supportsHistory(metric) && setting.loaded() && setting.enabled()) {
                return true;
            }
        }
        return stepFailed || (sleepFailed && sleepSyncEnabled && capabilities.newSleepProtocol);
    }

    private void configurePeriodicSync(boolean enabled, int intervalMinutes) {
        if (!PeriodicSyncPolicy.isSupportedInterval(intervalMinutes)) {
            periodicSyncStatus = "Unsupported interval";
            publishSnapshot();
            return;
        }
        periodicSyncEnabled = enabled;
        periodicSyncIntervalMinutes = intervalMinutes;
        preferences.edit()
                .putBoolean(PREF_PERIODIC_SYNC_ENABLED, enabled)
                .putInt(PREF_PERIODIC_SYNC_INTERVAL, intervalMinutes)
                .apply();
        worker.removeCallbacks(periodicSyncDue);
        if (enabled) {
            periodicSyncStatus = "Scheduled";
            scheduleNextPeriodicSync(System.currentTimeMillis());
        } else {
            clearPendingPeriodicSync();
            periodicSyncStatus = "Disabled";
            nextPeriodicSyncAt = 0L;
            preferences.edit().putLong(PREF_PERIODIC_SYNC_NEXT, 0L).apply();
            publishSnapshot();
        }
    }

    private void ensurePeriodicSyncScheduled() {
        worker.removeCallbacks(periodicSyncDue);
        if (!periodicSyncEnabled) return;
        if (periodicSyncQueue.isPending()) {
            worker.post(periodicSyncDue);
            publishSnapshot();
            return;
        }
        long now = System.currentTimeMillis();
        if (nextPeriodicSyncAt <= 0L) {
            nextPeriodicSyncAt = PeriodicSyncPolicy.deadlineAfter(now, periodicSyncIntervalMinutes);
            preferences.edit().putLong(PREF_PERIODIC_SYNC_NEXT, nextPeriodicSyncAt).apply();
        }
        worker.postDelayed(periodicSyncDue, Math.max(0L, nextPeriodicSyncAt - now));
        publishSnapshot();
    }

    private void handlePeriodicSyncDue() {
        if (!periodicSyncEnabled) return;
        if (periodicSyncQueue.isPending()) {
            tryStartPendingPeriodicSync();
            return;
        }
        long now = System.currentTimeMillis();
        if (nextPeriodicSyncAt > now) {
            ensurePeriodicSyncScheduled();
            return;
        }
        startHistorySync(SyncTrigger.PERIODIC);
    }

    private void queuePeriodicSync(String reason) {
        periodicSyncQueue.enqueue();
        periodicSyncStatus = "Queued: " + reason;
        preferences.edit().putBoolean(PREF_PERIODIC_SYNC_PENDING, true).apply();
        worker.removeCallbacks(periodicSyncDue);
        diagnostic("periodic sync queued: " + reason);
    }

    private void clearPendingPeriodicSync() {
        if (!periodicSyncQueue.isPending()) return;
        periodicSyncQueue.clear();
        preferences.edit().putBoolean(PREF_PERIODIC_SYNC_PENDING, false).apply();
    }

    private void tryStartPendingPeriodicSync() {
        if (!periodicSyncQueue.isPending()) return;
        if (!periodicSyncEnabled) {
            clearPendingPeriodicSync();
            return;
        }
        boolean transportIdle = connectionState == ConnectionState.READY
                && gattConnected && notificationsReady && !syncing
                && activeMeasurement == null && activeControl == null
                && controlRequests.isEmpty() && !autoSettingsUpdating;
        if (!periodicSyncQueue.pollIfIdle(transportIdle)) return;
        preferences.edit().putBoolean(PREF_PERIODIC_SYNC_PENDING, false).apply();
        diagnostic("starting queued periodic sync");
        startHistorySync(SyncTrigger.PERIODIC);
    }

    private void schedulePeriodicRetry(String reason) {
        if (!periodicSyncEnabled) return;
        periodicSyncStatus = "Retry: " + reason + " in "
                + PeriodicSyncPolicy.RETRY_INTERVAL_MINUTES + " min";
        nextPeriodicSyncAt = PeriodicSyncPolicy.retryDeadlineAfter(System.currentTimeMillis());
        preferences.edit().putLong(PREF_PERIODIC_SYNC_NEXT, nextPeriodicSyncAt).apply();
        worker.removeCallbacks(periodicSyncDue);
        worker.postDelayed(periodicSyncDue,
                Math.max(0L, nextPeriodicSyncAt - System.currentTimeMillis()));
        publishSnapshot();
    }

    private void scheduleNextPeriodicSync(long fromEpochMs) {
        worker.removeCallbacks(periodicSyncDue);
        if (!periodicSyncEnabled) return;
        nextPeriodicSyncAt = PeriodicSyncPolicy.deadlineAfter(fromEpochMs,
                periodicSyncIntervalMinutes);
        preferences.edit().putLong(PREF_PERIODIC_SYNC_NEXT, nextPeriodicSyncAt).apply();
        worker.postDelayed(periodicSyncDue, Math.max(0L, nextPeriodicSyncAt - System.currentTimeMillis()));
        publishSnapshot();
    }

    private void scheduleNextPeriodicSyncFromMetrics(long nowEpochMs) {
        worker.removeCallbacks(periodicSyncDue);
        if (!periodicSyncEnabled || periodicSyncQueue.isPending()) return;
        long earliest = Long.MAX_VALUE;
        for (HealthMetric metric : HealthMetric.values()) {
            AutoMeasurementSettings.MetricSetting setting = autoSettingFor(metric);
            if (!capabilities.supportsHistory(metric) || !setting.loaded() || !setting.enabled()) {
                continue;
            }
            long candidate = PeriodicSyncPolicy.nextMetricDeadline(
                    lastHistorySyncAt.getOrDefault(metric, 0L), nowEpochMs,
                    periodicSyncIntervalMinutes);
            earliest = Math.min(earliest, candidate);
        }
        if (sleepSyncEnabled && capabilities.newSleepProtocol) {
            int sleepInterval = Math.max(periodicSyncIntervalMinutes,
                    SLEEP_MIN_SYNC_INTERVAL_MINUTES);
            long candidate = PeriodicSyncPolicy.nextMetricDeadline(lastSleepSyncAt, nowEpochMs,
                    sleepInterval);
            earliest = Math.min(earliest, candidate);
        }
        long stepCandidate = PeriodicSyncPolicy.nextMetricDeadline(lastStepSyncAt, nowEpochMs,
                periodicSyncIntervalMinutes);
        earliest = Math.min(earliest, stepCandidate);
        if (earliest == Long.MAX_VALUE) {
            earliest = PeriodicSyncPolicy.deadlineAfter(nowEpochMs,
                    periodicSyncIntervalMinutes);
        }
        nextPeriodicSyncAt = Math.max(nowEpochMs, earliest);
        preferences.edit().putLong(PREF_PERIODIC_SYNC_NEXT, nextPeriodicSyncAt).apply();
        worker.postDelayed(periodicSyncDue,
                Math.max(0L, nextPeriodicSyncAt - System.currentTimeMillis()));
        publishSnapshot();
    }

    private boolean startGattOperation(GattOperation operation) {
        if (gatt == null || !gattConnected) {
            if (operation.onFailure != null) operation.onFailure.run();
            return false;
        }
        boolean submitted;
        if (operation.descriptor != null) {
            if (Build.VERSION.SDK_INT >= 33) {
                submitted = gatt.writeDescriptor(operation.descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
            } else {
                operation.descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                submitted = gatt.writeDescriptor(operation.descriptor);
            }
        } else {
            operation.characteristic.setWriteType(operation.writeType);
            if (Build.VERSION.SDK_INT >= 33) {
                submitted = gatt.writeCharacteristic(operation.characteristic, operation.value,
                        operation.writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                operation.characteristic.setValue(operation.value);
                submitted = gatt.writeCharacteristic(operation.characteristic);
            }
        }
        if (submitted) {
            worker.removeCallbacks(gattOperationTimeout);
            worker.postDelayed(gattOperationTimeout, GATT_OPERATION_TIMEOUT_MS);
        } else if (operation.onFailure != null) {
            operation.onFailure.run();
        }
        return submitted;
    }

    private void completeGattOperation(int status, String source) {
        worker.removeCallbacks(gattOperationTimeout);
        GattOperation operation = gattQueue.active();
        if (operation == null) {
            diagnostic("unexpected GATT callback " + source);
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (operation.onSuccess != null) operation.onSuccess.run();
        } else {
            diagnostic("GATT failure status=" + status + " " + operation.label);
            if (operation.onFailure != null) operation.onFailure.run();
        }
        gattQueue.complete();
    }

    private void handleDisconnected(String reason) {
        boolean wasReady = connectionState == ConnectionState.READY;
        gattConnected = false;
        notificationsReady = false;
        notificationDescriptorsRemaining = 0;
        worker.removeCallbacks(beginNotificationsFallback);
        worker.removeCallbacks(gattOperationTimeout);
        worker.removeCallbacks(controlTimeout);
        worker.removeCallbacks(historyTimeout);
        worker.removeCallbacks(sleepCompanionTimeout);
        gattQueue.clear();
        controlRequests.clear();
        activeControl = null;
        largeReassembler.reset();
        if (activeMeasurement != null) finishMeasurement("Disconnected", false, false);
        if (autoSettingsUpdating) failAutoSettings("disconnected");
        if (syncing) {
            syncFailed.addAll(syncMetrics);
            if (historyMetric != null) syncFailed.add(historyMetric);
            if (stepSyncQueued || stepSyncActive) stepFailed = true;
            stepSyncActive = false;
            stepSyncQueued = false;
            stepDetailAssembler = null;
            if (sleepSyncQueued || sleepSyncActive) sleepFailed = true;
            sleepSyncActive = false;
            sleepSyncQueued = false;
            finishHistorySync();
        }
        closeGatt();
        diagnostic("disconnected " + reason);
        setState(wasReady ? ConnectionState.WAITING_FOR_WAKE : ConnectionState.DISCONNECTED_RETRYING, reason);
        scheduleReconnect(reason);
    }

    private void disconnectAndRetry(String reason) {
        if (gatt != null) {
            try { gatt.disconnect(); } catch (RuntimeException ignored) {}
        }
        handleDisconnected(reason);
    }

    private void scheduleReconnect(String reason) {
        if (!started) return;
        int index = Math.min(reconnectAttempt, RECONNECT_BACKOFF_MS.length - 1);
        long delay = RECONNECT_BACKOFF_MS[index];
        reconnectAttempt++;
        setState(ConnectionState.DISCONNECTED_RETRYING,
                reason + "; retry in " + delay / 1000.0 + "s");
        worker.postDelayed(() -> {
            if (!started || gattConnected || scanning) return;
            if (reconnectAttempt >= 3) startScan();
            else if (targetDevice != null) connectGatt(targetDevice);
            else connectIfPossible();
        }, delay);
    }

    private void closeGatt() {
        BluetoothGatt old = gatt;
        gatt = null;
        controlWrite = null;
        controlNotify = null;
        largeWrite = null;
        largeNotify = null;
        if (old != null) {
            try { old.close(); } catch (RuntimeException ignored) {}
        }
    }

    private void rememberTarget(BluetoothDevice device) {
        targetDevice = device;
        ringAddress = device.getAddress();
        ringName = safeName(device);
        bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
        preferences.edit().putString("ring_address", ringAddress).putString("ring_name", ringName).apply();
        publishSnapshot();
    }

    private boolean matchesTarget(BluetoothDevice device) {
        if (device == null || !hasConnectPermission()) return false;
        if (!ringAddress.isEmpty() && ringAddress.equalsIgnoreCase(device.getAddress())) return true;
        return ringAddress.isEmpty() && isR08(device);
    }

    private boolean isR08(BluetoothDevice device) {
        if (device == null || !hasConnectPermission()) return false;
        String name = device.getName();
        return name != null && name.toUpperCase(Locale.US).startsWith("R08");
    }

    private String safeName(BluetoothDevice device) {
        if (device == null || !hasConnectPermission()) return "R08";
        String name = device.getName();
        return name == null || name.isBlank() ? "R08" : name;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String ringId() { return ringAddress.isEmpty() ? "R08" : ringAddress; }

    private static SharedPreferences healthPreferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences("r08-health", Context.MODE_PRIVATE);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static String metricSyncPreferenceKey(HealthMetric metric) {
        return PREF_METRIC_SYNC_LAST_PREFIX + metric.name();
    }

    private void scheduleLocalMidnightReset() {
        worker.removeCallbacks(localMidnight);
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        long nextMidnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli();
        worker.postDelayed(localMidnight, Math.max(1L, nextMidnight - now));
    }

    private void refreshStorage() {
        databaseExecutor.execute(() -> {
            List<HealthSampleEntity> recent = dao.samplesSince(
                    System.currentTimeMillis() - CHART_HISTORY_WINDOW_MS);
            List<SleepSessionEntity> recentSleep = dao.recentSleepSessions(30);
            List<StepDayEntity> recentSteps = dao.recentStepDays(30);
            String todayDate = LocalDate.now(ZoneId.systemDefault()).toString();
            StepDayEntity storedToday = dao.stepDay(todayDate);
            SleepSessionEntity nextLatestSleep = dao.latestSleepSession();
            EnumMap<HealthMetric, HealthSampleEntity> nextLatest = new EnumMap<>(HealthMetric.class);
            for (HealthMetric metric : HealthMetric.values()) {
                HealthSampleEntity item = dao.latest(metric.name());
                if (item != null) nextLatest.put(metric, item);
            }
            worker.post(() -> {
                history.clear();
                history.addAll(recent);
                latest.clear();
                latest.putAll(nextLatest);
                sleepHistory.clear();
                sleepHistory.addAll(recentSleep);
                stepHistory.clear();
                stepHistory.addAll(recentSteps);
                todaySteps = storedToday == null ? 0 : storedToday.steps;
                latestSleep = nextLatestSleep;
                publishSnapshot();
            });
        });
    }

    private void setState(ConnectionState state, String detail) {
        connectionState = state;
        diagnostic(state + ": " + detail);
        publishSnapshot();
        if (state == ConnectionState.READY && periodicSyncEnabled
                && (periodicSyncQueue.isPending()
                || (nextPeriodicSyncAt > 0L && nextPeriodicSyncAt <= System.currentTimeMillis()))) {
            worker.removeCallbacks(periodicSyncDue);
            worker.post(periodicSyncDue);
        }
    }

    private void diagnostic(String message) {
        String line = String.format(Locale.US, "%tT.%<tL %s", System.currentTimeMillis(), message);
        Log.d(TAG, line);
        diagnostics.addFirst(line);
        while (diagnostics.size() > 60) diagnostics.removeLast();
    }

    private void publishSnapshot() {
        AutoMeasurementSettings autoSettings = new AutoMeasurementSettings(
                autoSettingFor(HealthMetric.HEART_RATE),
                autoSettingFor(HealthMetric.SPO2),
                autoSettingFor(HealthMetric.TEMPERATURE),
                autoSettingsUpdating, autoSettingsStatus);
        RingHealthSnapshot snapshot = new RingHealthSnapshot(connectionState, ringName, ringAddress,
                bonded, gattConnected, notificationsReady, batteryPercent, batteryCharging, capabilities,
                autoSettings,
                activeMeasurement, measurementStatus,
                measurementStartedAtEpochMs, measurementDeadlineAtEpochMs,
                syncing, syncStatus,
                periodicSyncEnabled, periodicSyncIntervalMinutes, periodicSyncStatus,
                lastPeriodicSyncAt, nextPeriodicSyncAt, lastHistorySyncAt,
                sleepSyncEnabled, lastSleepSyncAt,
                lastStepSyncAt, todaySteps, stepHistory, latestSleep, sleepHistory,
                latest, history, new ArrayList<>(diagnostics));
        main.post(() -> {
            for (Listener listener : listeners) listener.onSnapshot(snapshot);
        });
    }

    private static String joinMetrics(Iterable<HealthMetric> metrics) {
        StringBuilder result = new StringBuilder();
        for (HealthMetric metric : metrics) {
            if (result.length() > 0) result.append(',');
            result.append(metric.name());
        }
        return result.toString();
    }

    private static String joinSyncTargets(Iterable<HealthMetric> metrics, boolean includeSteps,
                                          boolean includeSleep) {
        StringBuilder result = new StringBuilder(joinMetrics(metrics));
        if (includeSteps) {
            if (result.length() > 0) result.append(',');
            result.append("STEPS");
        }
        if (includeSleep) {
            if (result.length() > 0) result.append(',');
            result.append("SLEEP");
        }
        return result.toString();
    }

    private static String hexByte(int value) {
        return value < 0 ? "none" : String.format(Locale.US, "0x%02X", value);
    }

    private static final class ControlRequest {
        final String label;
        final int expectedOpcode;
        final byte[] frame;
        final ResponseHandler responseHandler;
        final Runnable onFinished;
        final Runnable onTimeout;
        final long timeoutMs;

        private ControlRequest(String label, int expectedOpcode, byte[] frame,
                               ResponseHandler responseHandler, Runnable onFinished,
                               Runnable onTimeout, long timeoutMs) {
            this.label = label;
            this.expectedOpcode = expectedOpcode;
            this.frame = frame;
            this.responseHandler = responseHandler;
            this.onFinished = onFinished;
            this.onTimeout = onTimeout;
            this.timeoutMs = timeoutMs;
        }

        static ControlRequest response(String label, int opcode, byte[] frame,
                                       ResponseHandler handler, Runnable finished,
                                       boolean continueOnTimeout) {
            return response(label, opcode, frame, handler, finished, continueOnTimeout, CONTROL_TIMEOUT_MS);
        }

        static ControlRequest response(String label, int opcode, byte[] frame,
                                       ResponseHandler handler, Runnable finished,
                                       boolean continueOnTimeout, long timeout) {
            Runnable timeoutAction = continueOnTimeout ? finished : null;
            return new ControlRequest(label, opcode, frame, handler, finished, timeoutAction, timeout);
        }

        static ControlRequest response(String label, int opcode, byte[] frame,
                                       ResponseHandler handler, Runnable finished,
                                       Runnable timeoutAction, long timeout) {
            return new ControlRequest(label, opcode, frame, handler, finished, timeoutAction, timeout);
        }

        static ControlRequest fireAndForget(String label, byte[] frame) {
            return new ControlRequest(label, -1, frame, null, null, null, CONTROL_TIMEOUT_MS);
        }
    }

    private static final class GattOperation {
        final String label;
        final BluetoothGattDescriptor descriptor;
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;
        final int writeType;
        final Runnable onSuccess;
        final Runnable onFailure;

        private GattOperation(String label, BluetoothGattDescriptor descriptor,
                              BluetoothGattCharacteristic characteristic, byte[] value,
                              int writeType, Runnable onSuccess, Runnable onFailure) {
            this.label = label;
            this.descriptor = descriptor;
            this.characteristic = characteristic;
            this.value = value;
            this.writeType = writeType;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }

        static GattOperation descriptor(String label, BluetoothGattDescriptor descriptor,
                                        Runnable success, Runnable failure) {
            return new GattOperation(label, descriptor, null, null, 0, success, failure);
        }

        static GattOperation characteristic(String label, BluetoothGattCharacteristic characteristic,
                                            byte[] value, int writeType,
                                            Runnable success, Runnable failure) {
            return new GattOperation(label, null, characteristic, value.clone(), writeType, success, failure);
        }
    }
}
