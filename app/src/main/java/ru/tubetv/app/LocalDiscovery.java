package ru.tubetv.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Short-lived mDNS advertisement and browsing while RussTube is foregrounded. */
final class LocalDiscovery {
    static final String SERVICE_TYPE = "_russtube._tcp.";

    interface Listener {
        void found(String deviceId, String name, String type, InetAddress address, int port);
        void lost(String deviceId);
    }

    private final Context context;
    private final NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicastLock;

    LocalDiscovery(Context context) {
        this.context = context.getApplicationContext();
        nsd = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    synchronized void advertise(int port, String deviceId, String name, String type)
            throws Exception {
        if (registration != null) return;
        if (nsd == null || port < 1 || port > 65535 || !LocalTrustedDevices.validId(deviceId))
            throw new Exception("Обнаружение в локальной сети недоступно");
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceType(SERVICE_TYPE);
        info.setServiceName("RussTube-" + deviceId.substring(0, 8));
        info.setPort(port);
        info.setAttribute("id", deviceId);
        info.setAttribute("name", name.length() > 48 ? name.substring(0, 48) : name);
        info.setAttribute("type", type);
        info.setAttribute("version", "1");
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) { }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int code) {
                synchronized (LocalDiscovery.this) { registration = null; }
            }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int code) { }
        };
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration); }
        catch (RuntimeException error) { registration = null; throw error; }
    }

    synchronized void browse(Listener listener) throws Exception {
        if (discovery != null) return;
        if (nsd == null) throw new Exception("Поиск устройств недоступен");
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            try {
                multicastLock = wifi.createMulticastLock("russtube-local-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            } catch (RuntimeException ignored) { multicastLock = null; }
        }
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int code) { stopBrowse(); }
            @Override public void onStopDiscoveryFailed(String serviceType, int code) { }
            @Override public void onDiscoveryStopped(String serviceType) { }
            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                try { nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int code) { }
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        String id = attribute(info, "id");
                        if (LocalTrustedDevices.validId(id) && info.getHost() != null
                                && info.getPort() > 0)
                            listener.found(id, attribute(info, "name"),
                                    attribute(info, "type"), info.getHost(), info.getPort());
                    }
                }); } catch (RuntimeException ignored) { }
            }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                String id = attribute(serviceInfo, "id");
                if (LocalTrustedDevices.validId(id)) listener.lost(id);
            }
        };
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (RuntimeException error) { stopBrowse(); throw error; }
    }

    synchronized void stopBrowse() {
        if (discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
            discovery = null;
        }
        if (multicastLock != null) {
            if (multicastLock.isHeld()) multicastLock.release();
            multicastLock = null;
        }
    }

    synchronized void stop() {
        stopBrowse();
        if (registration != null) {
            try { nsd.unregisterService(registration); } catch (RuntimeException ignored) { }
            registration = null;
        }
    }

    private static String attribute(NsdServiceInfo info, String key) {
        byte[] bytes = info.getAttributes().get(key);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
