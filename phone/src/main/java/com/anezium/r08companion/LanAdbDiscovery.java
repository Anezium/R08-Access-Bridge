package com.anezium.r08companion;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dadb.AdbKeyPair;
import dadb.Dadb;

final class LanAdbDiscovery {
    private static final int DISCOVERY_THREADS = 16;
    private static final int DISCOVERY_PORT_TIMEOUT_MS = 140;
    private static final int DISCOVERY_ADB_TIMEOUT_MS = 2500;
    private static final int DISCOVERY_TOTAL_TIMEOUT_MS = 22000;

    private final AdbBridgeClient bridgeClient;

    LanAdbDiscovery(AdbBridgeClient bridgeClient) {
        this.bridgeClient = bridgeClient;
    }

    String findGlassesHost(int port) throws Exception {
        String prefix = localWifiSubnetPrefix();
        if (prefix == null) {
            throw new IOException("Phone has no private Wi-Fi/LAN IPv4 address");
        }

        AdbKeyPair keyPair = bridgeClient.readOrCreateKeyPair();
        ExecutorService scanPool = Executors.newFixedThreadPool(DISCOVERY_THREADS);
        CompletionService<String> completion = new ExecutorCompletionService<>(scanPool);
        int submitted = 0;
        try {
            for (int i = 1; i <= 254; i++) {
                completion.submit(new ProbeTask(prefix + i, port, keyPair));
                submitted++;
            }

            long deadline = System.currentTimeMillis() + DISCOVERY_TOTAL_TIMEOUT_MS;
            for (int i = 0; i < submitted; i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                Future<String> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) {
                    break;
                }
                try {
                    String host = future.get();
                    if (host != null) {
                        scanPool.shutdownNow();
                        return host;
                    }
                } catch (ExecutionException ignored) {
                    // Keep scanning.
                }
            }
            return null;
        } finally {
            scanPool.shutdownNow();
        }
    }

    private boolean isAdbPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), DISCOVERY_PORT_TIMEOUT_MS);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private String localWifiSubnetPrefix() {
        List<String> candidates = localPrivateIpv4Addresses();
        String best = candidates.isEmpty() ? null : candidates.get(0);
        if (best == null) {
            return null;
        }
        int lastDot = best.lastIndexOf('.');
        return lastDot > 0 ? best.substring(0, lastDot + 1) : null;
    }

    private List<String> localPrivateIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (!isWifiLikeInterface(name)) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    java.net.InetAddress address = inetAddresses.nextElement();
                    if (address instanceof Inet4Address && isPrivateLanAddress(address.getHostAddress())) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // Caller handles no candidates.
        }
        return addresses;
    }

    private boolean isWifiLikeInterface(String name) {
        String lowered = name == null ? "" : name.toLowerCase();
        return lowered.equals("wlan0")
                || lowered.startsWith("wlan")
                || lowered.startsWith("ap")
                || lowered.contains("wifi")
                || lowered.contains("softap")
                || lowered.contains("swlan");
    }

    private boolean isPrivateLanAddress(String hostAddress) {
        if (hostAddress == null) {
            return false;
        }
        if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.")) {
            return true;
        }
        String[] octets = hostAddress.split("\\.");
        if (octets.length < 2) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 172 && second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private final class ProbeTask implements Callable<String> {
        private final String host;
        private final int port;
        private final AdbKeyPair keyPair;

        ProbeTask(String host, int port, AdbKeyPair keyPair) {
            this.host = host;
            this.port = port;
            this.keyPair = keyPair;
        }

        @Override
        public String call() {
            if (!isAdbPortOpen(host, port)) {
                return null;
            }
            try (Dadb dadb = Dadb.create(host, port, keyPair,
                    DISCOVERY_ADB_TIMEOUT_MS, DISCOVERY_ADB_TIMEOUT_MS, false)) {
                return bridgeClient.isRokidGlasses(dadb) ? host : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
