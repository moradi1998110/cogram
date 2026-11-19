package org.telegram.cogram;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.core.util.Consumer;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.ILocationServiceProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Telegram's location service on Android's own location manager, in place of Google's fused location.
 * Positions come from the phone's GPS (and the passive provider, which only reuses fixes other apps asked
 * for), so no location request leaves the phone for Google's servers.
 */
public class DeviceLocationProvider implements ILocationServiceProvider {

    private LocationManager manager;
    private final Map<ILocationListener, LocationListener> listeners = new HashMap<>();

    @Override
    public void init(Context context) {
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public ILocationRequest onCreateLocationRequest() {
        return new Request();
    }

    @Override
    public IMapApiClient onCreateLocationServicesAPI(Context context, IAPIConnectionCallbacks connectionCallbacks, IAPIOnConnectionFailedListener failedListener) {
        return new IMapApiClient() {
            @Override
            public void connect() {
                connectionCallbacks.onConnected(null);
            }

            @Override
            public void disconnect() {
            }
        };
    }

    @Override
    public boolean checkServices() {
        return manager != null;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void getLastLocation(Consumer<Location> callback) {
        Location best = null;
        if (manager != null) {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location location = manager.getLastKnownLocation(provider);
                    if (location != null && (best == null || location.getTime() > best.getTime())) {
                        best = location;
                    }
                } catch (Exception e) {
                    FileLog.e(e, false);
                }
            }
        }
        callback.accept(best);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void requestLocationUpdates(ILocationRequest request, ILocationListener locationListener) {
        if (manager == null) {
            return;
        }
        Request settings = (Request) request;
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                locationListener.onLocationChanged(location);
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
        removeLocationUpdates(locationListener);
        listeners.put(locationListener, listener);
        String provider = settings.priority == PRIORITY_HIGH_ACCURACY || settings.priority == PRIORITY_BALANCED_POWER_ACCURACY
                ? LocationManager.GPS_PROVIDER : LocationManager.PASSIVE_PROVIDER;
        try {
            manager.requestLocationUpdates(provider, Math.max(settings.fastestInterval, 1000), 0, listener, Looper.getMainLooper());
        } catch (Exception e) {
            FileLog.e(e, false);
        }
    }

    @Override
    public void removeLocationUpdates(ILocationListener locationListener) {
        LocationListener listener = listeners.remove(locationListener);
        if (listener != null && manager != null) {
            manager.removeUpdates(listener);
        }
    }

    @Override
    public void checkLocationSettings(ILocationRequest request, Consumer<Integer> callback) {
        boolean enabled = false;
        try {
            enabled = manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            FileLog.e(e, false);
        }
        callback.accept(enabled ? STATUS_SUCCESS : STATUS_SETTINGS_CHANGE_UNAVAILABLE);
    }

    private static final class Request implements ILocationRequest {
        int priority = PRIORITY_BALANCED_POWER_ACCURACY;
        long interval = 1000;
        long fastestInterval = 1000;

        @Override
        public void setPriority(int priority) {
            this.priority = priority;
        }

        @Override
        public void setInterval(long interval) {
            this.interval = interval;
        }

        @Override
        public void setFastestInterval(long interval) {
            this.fastestInterval = interval;
        }
    }
}
