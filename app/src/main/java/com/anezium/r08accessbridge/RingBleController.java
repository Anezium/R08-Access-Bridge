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

    private static final byte OPCODE_TOUCH_CONTROL = 0x3B;
    private static final byte APP_TYPE_MUSIC_KEYS = 0x01;
    private static final byte APP_TYPE_EBOOK_TOUCH = 0x04;
    private static final int PACKET_SIZE = 16;
    private static final long SCAN_TIMEOUT_MS = 25_000L;
    private static final long KEEPALIVE_MS = 18_000L;
    private static final long RECONNECT_MS = 5_000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<byte[]> writes = new ArrayDeque<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothDevice targetDevice;
    private boolean started;
    private boolean touchMode;
    private boolean scanning;
    private boolean writing;

    private final Runnable scanTimeout = () -> {
        if (scanning) {
            stopScan();
            Log.d(TAG, "R08 scan timed out");
            scheduleReconnect();
        }
    };

    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!started || writeCharacteristic == null) {
                return;
            }
            sendTpSleepWake();
            handler.postDelayed(this, KEEPALIVE_MS);
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
            // Writes are enough for mode control; avoiding CCCD writes keeps the GATT queue deterministic.
            Log.d(TAG, "R08 GATT ready");
            configureCurrentMode();
            handler.removeCallbacks(keepAlive);
            handler.postDelayed(keepAlive, KEEPALIVE_MS);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "GATT write status=" + status);
            writing = false;
            handler.postDelayed(RingBleController.this::drainWrites, 90);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) {
                Log.d(TAG, "Notify len=" + value.length);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            Log.d(TAG, "Notify len=" + (value == null ? 0 : value.length));
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
        if (writeCharacteristic == null) {
            Log.d(TAG, "Fast key config delayed: GATT not ready");
            return;
        }
        enqueue(touchConfig(APP_TYPE_MUSIC_KEYS, 5));
        handler.postDelayed(() -> enqueue(gestureConfig((byte) 0, (byte) 0)), 260);
        handler.postDelayed(this::sendTpSleepWake, 540);
        Log.d(TAG, "Configured R08 fast key mode appType=1, gesture off");
    }

    private void configureCurrentMode() {
        if (touchMode) {
            configureTouchMode();
        } else {
            configureGestureMode();
        }
    }

    private void sendTpSleepWake() {
        enqueue(tpSleepWake(APP_TYPE_EBOOK_TOUCH, (byte) 1));
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
        handler.removeCallbacks(keepAlive);
        writeCharacteristic = null;
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

    private void enableNotifications(BluetoothGattCharacteristic notifyCharacteristic) {
        if (notifyCharacteristic == null || gatt == null) {
            return;
        }
        gatt.setCharacteristicNotification(notifyCharacteristic, true);
        BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }
    }

    private void enqueue(byte[] packet) {
        if (packet == null || writeCharacteristic == null || gatt == null) {
            return;
        }
        writes.add(packet);
        drainWrites();
    }

    private void drainWrites() {
        if (writing || writeCharacteristic == null || gatt == null || writes.isEmpty()) {
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
