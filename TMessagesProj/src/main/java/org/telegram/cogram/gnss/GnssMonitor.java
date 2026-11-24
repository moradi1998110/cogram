package org.telegram.cogram.gnss;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the phone's satellite receiver says while the Maps tab shows: the position's accuracy, and the satellites in
 * view, each with its system, its signal and whether it is used in the position. It listens to the GPS fixes the map
 * already asks for, so it keeps no receiver on by itself; Android 6 has no satellite report, only the accuracy.
 */
public final class GnssMonitor {

    public interface Listener {
        void onGnssChanged();
    }

    /** One system of satellites: GPS, GLONASS, Galileo, BeiDou... */
    public static final class Constellation {
        public final String name;
        public int visible;
        public int used;
        /** The mean signal of the ones used, in dB-Hz. */
        public float signal;

        Constellation(String name) {
            this.name = name;
        }
    }

    /** The quality levels, from no position to a good one. */
    public static final int NONE = 0, POOR = 1, FAIR = 2, GOOD = 3;

    private final List<Listener> listeners = new ArrayList<>();
    private LocationManager manager;
    private GnssStatus.Callback statusCallback;

    /** The last fix from the satellites, and the last from anything else (Wi-Fi, cell towers): kept apart, as the map
     * asks for both and a network fix coming a second after a GPS one must not hide it. */
    private Location location;
    private Location network;
    private final List<Constellation> systems = new ArrayList<>();
    private int visible;
    private int used;
    private boolean dualFrequency;
    private boolean reported;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location changed) {
            if (LocationManager.GPS_PROVIDER.equals(changed.getProvider())) {
                location = changed;
            } else {
                network = changed;
            }
            notifyListeners();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @SuppressLint("MissingPermission")
    public void start(Context context) {
        if (manager != null) return;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return;
        try {
            location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            // Passive: the map's own requests bring the fixes; this one turns no receiver on.
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 0, locationListener, Looper.getMainLooper());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                statusCallback = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        read(status);
                    }
                };
                manager.registerGnssStatusCallback(statusCallback, new android.os.Handler(Looper.getMainLooper()));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        notifyListeners();
    }

    public void stop() {
        if (manager == null) return;
        manager.removeUpdates(locationListener);
        if (statusCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.unregisterGnssStatusCallback(statusCallback);
        }
        statusCallback = null;
        manager = null;
    }

    /** The last GPS fix, or null; a fix older than a minute does not count. */
    public Location getLocation() {
        return recent(location);
    }

    /** The last fix from the network, or null: where the map's dot is while the satellites have not found it. */
    public Location getApproximate() {
        return recent(network);
    }

    private static Location recent(Location fix) {
        if (fix == null) return null;
        final long age = (android.os.SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000L;
        return age < 60_000 ? fix : null;
    }

    public int getQuality() {
        final Location fix = getLocation();
        if (fix == null || !fix.hasAccuracy()) return NONE;
        final float accuracy = fix.getAccuracy();
        if (accuracy <= 5 && (!reported || used >= 8)) return GOOD;
        if (accuracy <= 15 && (!reported || used >= 4)) return FAIR;
        return POOR;
    }

    /** How full the gauge is, from 0 (no position) to 1 (within 3 m). */
    public float getGauge() {
        final Location fix = getLocation();
        if (fix == null || !fix.hasAccuracy()) return 0;
        return Math.max(0.1f, Math.min(1, 1 - (fix.getAccuracy() - 3) / 30));
    }

    /** Whether the phone reports its satellites (Android 7 on, once a report came). */
    public boolean isReported() {
        return reported;
    }

    public int getVisible() {
        return visible;
    }

    public int getUsed() {
        return used;
    }

    public boolean isDualFrequency() {
        return dualFrequency;
    }

    public List<Constellation> getSystems() {
        return systems;
    }

    private void read(GnssStatus status) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        systems.clear();
        visible = status.getSatelliteCount();
        used = 0;
        dualFrequency = false;
        final float[] signals = new float[8];
        final Constellation[] byType = new Constellation[8];
        for (int i = 0; i < visible; i++) {
            final int type = Math.min(status.getConstellationType(i), 7);
            if (byType[type] == null) byType[type] = new Constellation(systemName(type));
            final Constellation system = byType[type];
            system.visible++;
            if (status.usedInFix(i)) {
                used++;
                system.used++;
                signals[type] += status.getCn0DbHz(i);
            }
            // L5 and E5a sit near 1176 MHz; L1 near 1575 MHz.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && status.hasCarrierFrequencyHz(i)
                    && status.getCarrierFrequencyHz(i) < 1.3e9f && status.usedInFix(i)) {
                dualFrequency = true;
            }
        }
        for (int type = 0; type < byType.length; type++) {
            if (byType[type] == null) continue;
            byType[type].signal = byType[type].used > 0 ? signals[type] / byType[type].used : 0;
            systems.add(byType[type]);
        }
        Collections.sort(systems, (a, b) -> b.used != a.used ? b.used - a.used : b.visible - a.visible);
        reported = true;
        notifyListeners();
    }

    private static String systemName(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BeiDou";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            case 7: return "NavIC";
            default: return "?";
        }
    }

    private void notifyListeners() {
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onGnssChanged();
        }
    }
}
