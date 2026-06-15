package com.anezium.r08accessbridge;

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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@SuppressLint("MissingPermission")
final class RingBleController {
    private static final String TAG = "R08Ble";

    private static final UUID SERVICE_UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID WRITE_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NOTIFY_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final byte OPCODE_BATTERY = 0x03;
    private static final byte OPCODE_TOUCH_CONTROL = 0x3B;
    private static final byte APP_TYPE_MUSIC_KEYS = 0x01;
    private static final byte APP_TYPE_EBOOK_TOUCH = 0x04;
    private static final int FLAG_MASK_ERROR = 0x80;
    private static final int PACKET_SIZE = 16;
    private static final long SCAN_TIMEOUT_MS = 25_000L;
    private static final long RECONNECT_MS = 5_000L;
    private static final long BATTERY_REFRESH_MS = 5 * 60_000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<byte[]> writes = new ArrayDeque<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothDevice targetDevice;
    private byte activeAppType = APP_TYPE_MUSIC_KEYS;
    private boolean started;
    private boolean touchMode;
    private boolean scanning;
    private boolean writing;
    private boolean descriptorWriting;
    private boolean notificationsEnabled;

    private final Runnable scanTimeout = () -> {
        if (scanning) {
            stopScan();
            Log.d(TAG, "R08 scan timed out");
            scheduleReconnect();
        }
    };

    private final Runnable batteryRefresh = new Runnable() {
        @Override
        public void run() {
            if (!started || !notificationsEnabled || writeCharacteristic == null) {
                return;
            }
            requestBattery("refresh");
            handler.postDelayed(this, BATTERY_REFRESH_MS);
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (!isR08(device)) {
                return;
            }
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            Log.d(TAG, "R08 bond state=" + state);
            if (state == BluetoothDevice.BOND_BONDED) {
                connect(device);
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!isR08(device)) {
                return;
            }
            Log.d(TAG, "Found R08 device name=" + safeName(device));
            stopScan();
            targetDevice = device;
            bondOrConnect(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            Log.w(TAG, "R08 scan failed code=" + errorCode);
            scheduleReconnect();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            Log.d(TAG, "GATT state=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = bluetoothGatt;
                writeCharacteristic = null;
                writes.clear();
                writing = false;
                descriptorWriting = false;
                notificationsEnabled = false;
                bluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt();
                scheduleReconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed status=" + status);
                scheduleReconnect();
                return;
            }
            BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.w(TAG, "R08 custom service missing");
                scheduleReconnect();
                return;
            }
            writeCharacteristic = service.getCharacteristic(WRITE_CHAR_UUID);
            if (writeCharacteristic == null) {
                Log.w(TAG, "R08 write characteristic missing");
                scheduleReconnect();
                return;
            }
            BluetoothGattCharacteristic notifyCharacteristic = service.getCharacteristic(NOTIFY_CHAR_UUID);
            if (!enableNotifications(notifyCharacteristic)) {
                Log.w(TAG, "R08 notifications unavailable; battery status disabled");
                onGattReady(false);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "GATT write status=" + status);
            writing = false;
            handler.postDelayed(RingBleController.this::drainWrites, 90);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(characteristic, value);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor descriptor, int status) {
            descriptorWriting = false;
            boolean success = status == BluetoothGatt.GATT_SUCCESS;
            notificationsEnabled = success;
            Log.d(TAG, "R08 notifications enabled=" + success + " status=" + status);
            onGattReady(success);
            drainWrites();
        }
    };

    RingBleController(Context context) {
        this.context = context.getApplicationContext();
    }

    void setTouchMode(boolean enabled) {
        touchMode = enabled;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter missing");
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(bondReceiver, filter);
        }
        BluetoothDevice bonded = findBondedR08();
        if (bonded != null) {
            targetDevice = bonded;
            connect(bonded);
        } else {
            startScan();
        }
    }

    void stop() {
        started = false;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    void restart() {
        stopScan();
        closeGatt();
        writes.clear();
        writing = false;
        if (targetDevice != null) {
            bondOrConnect(targetDevice);
        } else {
            BluetoothDevice bonded = findBondedR08();
            if (bonded != null) {
                targetDevice = bonded;
                connect(bonded);
            } else {
                startScan();
            }
        }
    }

    void requestBatteryNow() {
        requestBattery("manual");
    }

    boolean forgetBondedR08() {
        ensureAdapter();
        stopScan();
        closeGatt();
        targetDevice = null;
        writes.clear();
        writing = false;
        BluetoothDevice bonded = findBondedR08();
        if (bonded == null) {
            Log.d(TAG, "No bonded R08 to forget");
            return false;
        }
        try {
            Method removeBond = BluetoothDevice.class.getMethod("removeBond");
            boolean submitted = Boolean.TRUE.equals(removeBond.invoke(bonded));
            Log.d(TAG, "Forget R08 submitted=" + submitted + " name=" + safeName(bonded));
            return submitted;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Failed to forget bonded R08", e);
            return false;
        }
    }

    void configureTouchMode() {
        touchMode = true;
        activeAppType = APP_TYPE_EBOOK_TOUCH;
        if (writeCharacteristic == null) {
            Log.d(TAG, "Touch config delayed: GATT not ready");
            return;
        }
        enqueue(touchConfig(APP_TYPE_EBOOK_TOUCH, 5));
        handler.postDelayed(() -> enqueue(gestureConfig((byte) 0, (byte) 0)), 260);
        handler.postDelayed(this::sendTpSleepWake, 540);
        Log.d(TAG, "Configured R08 touch mode appType=4, gesture off");
    }

    void configureGestureMode() {
        touchMode = false;
        activeAppType = APP_TYPE_MUSIC_KEYS;
        if (writeCharacteristic == null) {
            Log.d(TAG, "Fast key config delayed: GATT not ready");
            return;
        }
        enqueue(touchConfig(APP_TYPE_MUSIC_KEYS, 5));
        handler.postDelayed(() -> enqueue(gestureConfig((byte) 0, (byte) 0)), 260);
        handler.postDelayed(this::sendTpSleepWake, 540);
        Log.d(TAG, "Configured R08 fast key mode appType=1, gesture off");
    }

    void configureProbeAppType(int appType) {
        touchMode = false;
        activeAppType = (byte) (appType & 0xFF);
        if (writeCharacteristic == null) {
            Log.d(TAG, "Probe appType delayed: GATT not ready appType=" + appType);
            return;
        }
        byte type = activeAppType;
        enqueue(touchConfig(type, 5));
        handler.postDelayed(() -> enqueue(gestureConfig((byte) 0, (byte) 0)), 260);
        handler.postDelayed(() -> enqueue(tpSleepWake(type, (byte) 1)), 540);
        Log.d(TAG, "Configured R08 probe appType=" + appType + ", gesture off");
    }

    private void configureCurrentMode() {
        if (touchMode) {
            configureTouchMode();
        } else {
            configureGestureMode();
        }
    }

    private void sendTpSleepWake() {
        enqueue(tpSleepWake(activeAppType, (byte) 1));
    }

    private void onGattReady(boolean canReadBattery) {
        Log.d(TAG, "R08 GATT ready notifications=" + canReadBattery);
        configureCurrentMode();
        if (canReadBattery) {
            requestBattery("gatt_ready");
            scheduleBatteryRefresh();
        }
    }

    private void ensureAdapter() {
        if (adapter != null) {
            return;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    private BluetoothDevice findBondedR08() {
        if (!hasConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission");
            return null;
        }
        Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
        if (bondedDevices == null) {
            return null;
        }
        for (BluetoothDevice device : bondedDevices) {
            if (isR08(device)) {
                Log.d(TAG, "Using bonded R08 name=" + safeName(device));
                return device;
            }
        }
        return null;
    }

    private void startScan() {
        if (!started || scanning) {
            return;
        }
        if (!hasScanPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG, "BLE scanner missing");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanning = true;
        scanner.startScan(null, settings, scanCallback);
        handler.removeCallbacks(scanTimeout);
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        Log.d(TAG, "Scanning for R08");
    }

    private void stopScan() {
        if (!scanning || scanner == null || !hasScanPermission()) {
            scanning = false;
            return;
        }
        scanner.stopScan(scanCallback);
        scanning = false;
        handler.removeCallbacks(scanTimeout);
    }

    private void bondOrConnect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission");
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "Creating R08 bond");
            if (!device.createBond()) {
                connect(device);
            }
        } else {
            connect(device);
        }
    }

    private void connect(BluetoothDevice device) {
        if (!started || !hasConnectPermission()) {
            return;
        }
        closeGatt();
        targetDevice = device;
        Log.d(TAG, "Connecting GATT to name=" + safeName(device));
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void closeGatt() {
        handler.removeCallbacks(batteryRefresh);
        writeCharacteristic = null;
        descriptorWriting = false;
        notificationsEnabled = false;
        if (gatt != null && hasConnectPermission()) {
            gatt.disconnect();
            gatt.close();
        }
        gatt = null;
    }

    private void scheduleReconnect() {
        if (!started) {
            return;
        }
        handler.postDelayed(() -> {
            if (targetDevice != null) {
                bondOrConnect(targetDevice);
            } else {
                startScan();
            }
        }, RECONNECT_MS);
    }

    private boolean enableNotifications(BluetoothGattCharacteristic notifyCharacteristic) {
        if (notifyCharacteristic == null || gatt == null) {
            return false;
        }
        if (!gatt.setCharacteristicNotification(notifyCharacteristic, true)) {
            Log.w(TAG, "R08 setCharacteristicNotification failed");
            return false;
        }
        BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            Log.w(TAG, "R08 notify CCCD missing");
            return false;
        }
        descriptorWriting = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (result == BluetoothGatt.GATT_SUCCESS) {
                return true;
            }
            Log.w(TAG, "R08 notify descriptor write returned=" + result);
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (gatt.writeDescriptor(descriptor)) {
                return true;
            }
            Log.w(TAG, "R08 notify descriptor write submit failed");
        }
        descriptorWriting = false;
        return false;
    }

    private void enqueue(byte[] packet) {
        if (packet == null || writeCharacteristic == null || gatt == null) {
            return;
        }
        writes.add(packet);
        drainWrites();
    }

    private void drainWrites() {
        if (descriptorWriting || writing || writeCharacteristic == null || gatt == null || writes.isEmpty()) {
            return;
        }
        byte[] packet = writes.poll();
        writing = true;
        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = gatt.writeCharacteristic(writeCharacteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (result != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT write returned=" + result);
                writing = false;
                handler.postDelayed(this::drainWrites, 160);
            }
        } else {
            writeCharacteristic.setValue(packet);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                Log.w(TAG, "GATT write submit failed");
                writing = false;
                handler.postDelayed(this::drainWrites, 160);
            }
        }
    }

    private void requestBattery(String reason) {
        if (!notificationsEnabled) {
            Log.d(TAG, "Battery request skipped: notifications not ready reason=" + reason);
            return;
        }
        Log.d(TAG, "Requesting ring battery reason=" + reason);
        enqueue(simpleCommand(OPCODE_BATTERY));
    }

    private void scheduleBatteryRefresh() {
        handler.removeCallbacks(batteryRefresh);
        handler.postDelayed(batteryRefresh, BATTERY_REFRESH_MS);
    }

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (characteristic == null || !NOTIFY_CHAR_UUID.equals(characteristic.getUuid())) {
            return;
        }
        int length = value == null ? 0 : value.length;
        Log.d(TAG, "Notify len=" + length);
        if (value == null || value.length < 4) {
            return;
        }
        if (!checkCrc(value)) {
            Log.w(TAG, "Notify ignored: crc mismatch len=" + value.length);
            return;
        }
        int command = (value[0] & 0xFF) & ~FLAG_MASK_ERROR;
        if (command == (OPCODE_BATTERY & 0xFF)) {
            parseBattery(value);
        }
    }

    private void parseBattery(byte[] value) {
        int percent = value[1] & 0xFF;
        if (percent > 100) {
            Log.w(TAG, "Ring battery ignored percent=" + percent);
            return;
        }
        boolean charging = value[2] == 1;
        RingBatteryStatus.save(context, percent, charging);
        Intent intent = new Intent(RingBatteryStatus.ACTION_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent, RingControlAccessibilityService.COMMAND_PERMISSION);
        Log.d(TAG, "Ring battery=" + percent + " charging=" + charging);
    }

    private byte[] simpleCommand(byte command) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = command;
        addCrc(packet);
        return packet;
    }

    private byte[] touchConfig(byte appType, int sleepMinutes) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = OPCODE_TOUCH_CONTROL;
        packet[1] = 0x02;
        packet[2] = 0x00;
        packet[3] = appType;
        packet[4] = (byte) sleepMinutes;
        addCrc(packet);
        return packet;
    }

    private byte[] gestureConfig(byte appType, byte strength) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = OPCODE_TOUCH_CONTROL;
        packet[1] = 0x02;
        packet[2] = 0x01;
        packet[3] = appType;
        packet[4] = strength;
        addCrc(packet);
        return packet;
    }

    private byte[] tpSleepWake(byte category, byte state) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = OPCODE_TOUCH_CONTROL;
        packet[1] = 0x02;
        packet[2] = 0x02;
        packet[3] = category;
        packet[4] = state;
        addCrc(packet);
        return packet;
    }

    private void addCrc(byte[] packet) {
        int crc = 0;
        for (int i = 0; i < packet.length - 1; i++) {
            crc += packet[i] & 0xFF;
        }
        packet[packet.length - 1] = (byte) (crc & 0xFF);
    }

    private boolean checkCrc(byte[] packet) {
        int crc = 0;
        for (int i = 0; i < packet.length - 1; i++) {
            crc += packet[i] & 0xFF;
        }
        return (packet[packet.length - 1] & 0xFF) == (crc & 0xFF);
    }

    private boolean isR08(BluetoothDevice device) {
        if (device == null || !hasConnectPermission()) {
            return false;
        }
        String name = device.getName();
        return name != null && name.toUpperCase(Locale.US).startsWith("R08");
    }

    private String safeName(BluetoothDevice device) {
        if (device == null || !hasConnectPermission()) {
            return "?";
        }
        String name = device.getName();
        return name == null ? "?" : name;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
}
