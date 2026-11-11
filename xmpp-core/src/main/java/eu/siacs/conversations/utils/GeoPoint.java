package eu.siacs.conversations.utils;

// Stands in for osmdroid's GeoPoint, the only class the engine used from that map library.
public final class GeoPoint {

    private final double latitude;
    private final double longitude;

    public GeoPoint(final double latitude, final double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}
