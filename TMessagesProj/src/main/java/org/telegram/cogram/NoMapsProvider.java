package org.telegram.cogram;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.util.Consumer;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.IMapsProvider;
import org.telegram.messenger.R;

import java.util.List;

/**
 * Telegram's maps without Google's map library, which Phogram does not ship: Phogram's maps are OsmAnd's, in
 * the Maps tab and wherever a chat shows a place. Telegram's own location screens are no longer opened; if one
 * still is, it shows an empty area instead of a map, and nothing reaches any server.
 */
public class NoMapsProvider implements IMapsProvider {

    @Override
    public void initializeMaps(Context context) {
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        final FrameLayout view = new FrameLayout(context);
        return new IMapView() {
            @Override
            public View getView() {
                return view;
            }

            @Override
            public void getMapAsync(Consumer<IMap> callback) {
                // There is no map, so Telegram's screen keeps waiting for one and draws nothing on it.
            }

            @Override
            public void onResume() {
            }

            @Override
            public void onPause() {
            }

            @Override
            public void onCreate(Bundle savedInstance) {
            }

            @Override
            public void onDestroy() {
            }

            @Override
            public void onLowMemory() {
            }

            @Override
            public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            }

            @Override
            public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            }

            @Override
            public void setOnLayoutListener(Runnable callback) {
            }
        };
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions() {
        return new IMarkerOptions() {
            @Override
            public IMarkerOptions position(LatLng latLng) {
                return this;
            }

            @Override
            public IMarkerOptions icon(Bitmap bitmap) {
                return this;
            }

            @Override
            public IMarkerOptions icon(int resId) {
                return this;
            }

            @Override
            public IMarkerOptions anchor(float lat, float lng) {
                return this;
            }

            @Override
            public IMarkerOptions title(String title) {
                return this;
            }

            @Override
            public IMarkerOptions snippet(String snippet) {
                return this;
            }

            @Override
            public IMarkerOptions flat(boolean flat) {
                return this;
            }
        };
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new ICircleOptions() {
            @Override
            public ICircleOptions center(LatLng latLng) {
                return this;
            }

            @Override
            public ICircleOptions radius(double radius) {
                return this;
            }

            @Override
            public ICircleOptions strokeColor(int color) {
                return this;
            }

            @Override
            public ICircleOptions fillColor(int color) {
                return this;
            }

            @Override
            public ICircleOptions strokePattern(List<PatternItem> patternItems) {
                return this;
            }

            @Override
            public ICircleOptions strokeWidth(int width) {
                return this;
            }
        };
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new ILatLngBoundsBuilder() {
            private double north = -90, south = 90, east = -180, west = 180;

            @Override
            public ILatLngBoundsBuilder include(LatLng latLng) {
                north = Math.max(north, latLng.latitude);
                south = Math.min(south, latLng.latitude);
                east = Math.max(east, latLng.longitude);
                west = Math.min(west, latLng.longitude);
                return this;
            }

            @Override
            public ILatLngBounds build() {
                final LatLng center = new LatLng((north + south) / 2, (east + west) / 2);
                return () -> center;
            }
        };
    }

    private static final ICameraUpdate NO_UPDATE = new ICameraUpdate() {
    };

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) {
        return NO_UPDATE;
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) {
        return NO_UPDATE;
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return NO_UPDATE;
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return new IMapStyleOptions() {
        };
    }

    /** The app itself, which is always installed, so Telegram never sends the user to install Google Maps. */
    @Override
    public String getMapsAppPackageName() {
        return ApplicationLoader.applicationContext.getPackageName();
    }

    @Override
    public int getInstallMapsString() {
        return R.string.InstallGoogleMaps;
    }
}
