package com.anezium.r08companion;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class AdbMdnsPairingResolver {
    private static final String TAG = "R08AdbMdns";
    private static final String ADB_TLS_PAIRING_TYPE = "_adb-tls-pairing._tcp.";
    private static final long DEFAULT_TIMEOUT_MS = 8000L;

    private final Context context;

    AdbMdnsPairingResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    PairingEndpoint resolvePairingEndpoint(String expectedHost) throws IOException {
        return resolvePairingEndpoint(expectedHost, DEFAULT_TIMEOUT_MS);
    }

    PairingEndpoint resolvePairingEndpoint(String expectedHost, long timeoutMs) throws IOException {
        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            throw new IOException("Android NSD service is unavailable");
        }
        DiscoveryRun run = new DiscoveryRun(nsdManager, cleanHost(expectedHost), timeoutMs);
        return run.execute();
    }

    static final class PairingEndpoint {
        final String host;
        final int port;

        PairingEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private final class DiscoveryRun {
        private final NsdManager nsdManager;
        private final String expectedHost;
        private final long timeoutMs;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Queue<NsdServiceInfo> pending = new ArrayDeque<>();
        private final Object lock = new Object();

        private PairingEndpoint result;
        private String failure = "";
        private boolean discoveryStarted;
        private boolean resolving;
        private boolean stopped;

        private final NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "pairing discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!isPairingService(serviceInfo)) {
                    return;
                }
                synchronized (lock) {
                    pending.add(serviceInfo);
                }
                pumpResolve();
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "pairing service lost: " + safeServiceName(serviceInfo));
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "pairing discovery stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                completeFailure("mDNS pairing discovery failed to start: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.d(TAG, "pairing discovery stop failed: " + errorCode);
            }
        };

        DiscoveryRun(NsdManager nsdManager, String expectedHost, long timeoutMs) {
            this.nsdManager = nsdManager;
            this.expectedHost = expectedHost;
            this.timeoutMs = timeoutMs;
        }

        PairingEndpoint execute() throws IOException {
            WifiManager.MulticastLock multicastLock = acquireMulticastLock();
            try {
                try {
                    nsdManager.discoverServices(
                            ADB_TLS_PAIRING_TYPE,
                            NsdManager.PROTOCOL_DNS_SD,
                            discoveryListener);
                    discoveryStarted = true;
                } catch (RuntimeException exception) {
                    throw new IOException("Could not start mDNS pairing discovery", exception);
                }

                try {
                    finished.await(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("mDNS pairing discovery was interrupted", exception);
                }

                synchronized (lock) {
                    if (result != null) {
                        return result;
                    }
                    if (!failure.isEmpty()) {
                        throw new IOException(failure);
                    }
                }
                String suffix = expectedHost.isEmpty() ? "" : " for " + expectedHost;
                throw new IOException("No Wireless Debugging pairing mDNS service found" + suffix);
            } finally {
                stopDiscovery();
                if (multicastLock != null && multicastLock.isHeld()) {
                    try {
                        multicastLock.release();
                    } catch (RuntimeException ignored) {
                        // Best-effort multicast cleanup.
                    }
                }
            }
        }

        private WifiManager.MulticastLock acquireMulticastLock() {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            try {
                WifiManager.MulticastLock lock = wifiManager.createMulticastLock("r08-adb-mdns-pairing");
                lock.setReferenceCounted(false);
                lock.acquire();
                return lock;
            } catch (RuntimeException exception) {
                Log.d(TAG, "multicast lock unavailable", exception);
                return null;
            }
        }

        private boolean isPairingService(NsdServiceInfo serviceInfo) {
            String serviceType = serviceInfo == null ? "" : serviceInfo.getServiceType();
            return serviceType != null
                    && serviceType.toLowerCase(Locale.US).contains("_adb-tls-pairing");
        }

        private void pumpResolve() {
            NsdServiceInfo next;
            synchronized (lock) {
                if (stopped || resolving || result != null) {
                    return;
                }
                next = pending.poll();
                if (next == null) {
                    return;
                }
                resolving = true;
            }
            try {
                nsdManager.resolveService(next, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        Log.d(TAG, "pairing service resolve failed: " + errorCode);
                        finishResolveAndContinue();
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        PairingEndpoint endpoint = endpointFrom(serviceInfo);
                        if (endpoint != null && hostMatches(endpoint.host)) {
                            Log.d(TAG, "pairing service resolved host=" + redactedHost(endpoint.host)
                                    + " port=" + endpoint.port);
                            complete(endpoint);
                            return;
                        }
                        if (endpoint != null) {
                            Log.d(TAG, "ignored pairing service host=" + redactedHost(endpoint.host)
                                    + " port=" + endpoint.port);
                        }
                        finishResolveAndContinue();
                    }
                });
            } catch (RuntimeException exception) {
                Log.d(TAG, "pairing service resolve threw", exception);
                finishResolveAndContinue();
            }
        }

        private PairingEndpoint endpointFrom(NsdServiceInfo serviceInfo) {
            if (serviceInfo == null || serviceInfo.getPort() <= 0) {
                return null;
            }
            InetAddress address = serviceInfo.getHost();
            if (!(address instanceof Inet4Address)) {
                return null;
            }
            String host = cleanHost(address.getHostAddress());
            return host.isEmpty() ? null : new PairingEndpoint(host, serviceInfo.getPort());
        }

        private boolean hostMatches(String host) {
            return expectedHost.isEmpty() || expectedHost.equals(cleanHost(host));
        }

        private void finishResolveAndContinue() {
            synchronized (lock) {
                resolving = false;
            }
            pumpResolve();
        }

        private void complete(PairingEndpoint endpoint) {
            synchronized (lock) {
                if (stopped || result != null) {
                    return;
                }
                result = endpoint;
                stopped = true;
            }
            finished.countDown();
        }

        private void completeFailure(String message) {
            synchronized (lock) {
                if (stopped || result != null) {
                    return;
                }
                failure = message == null ? "mDNS pairing discovery failed" : message;
                stopped = true;
            }
            finished.countDown();
        }

        private void stopDiscovery() {
            synchronized (lock) {
                if (!discoveryStarted) {
                    stopped = true;
                    return;
                }
                if (stopped && result == null && !failure.isEmpty()) {
                    // Still stop below; this branch only keeps the state explicit.
                }
                stopped = true;
                discoveryStarted = false;
            }
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (RuntimeException ignored) {
                // Android may throw if discovery already stopped or never fully started.
            }
        }
    }

    private static String cleanHost(String host) {
        return host == null ? "" : host.trim();
    }

    private static String safeServiceName(NsdServiceInfo serviceInfo) {
        String name = serviceInfo == null ? "" : serviceInfo.getServiceName();
        return name == null ? "" : name;
    }

    private static String redactedHost(String host) {
        String clean = cleanHost(host);
        int lastDot = clean.lastIndexOf('.');
        return lastDot > 0 ? clean.substring(0, lastDot + 1) + "x" : "redacted";
    }
}
