package org.telegram.cogram;

/**
 * Follows the driver along a route: each GPS position is matched to the nearest part of the route just ahead of the
 * last match, which gives the distance left, the next turn and the distance to it. A driver who stays away from the
 * route for a few positions has left it, and the map finds a new route from there.
 */
final class RouteNavigator {

    /** Farther than this from the route counts as away from it, a GPS's usual error included. */
    private static final float OFF_ROUTE_METERS = 45;
    /** Positions in a row away from the route before a new one is found. */
    private static final int OFF_ROUTE_COUNT = 3;
    /** Closer than this to the end is there. */
    private static final float ARRIVED_METERS = 30;
    /** How far back and ahead of the last match the route is searched, in points. */
    private static final int SEARCH_BACK = 5;
    private static final int SEARCH_AHEAD = 400;

    final MapEngine.Route route;
    private int index;
    private int offRoute;

    RouteNavigator(MapEngine.Route route) {
        this.route = route;
    }

    /** True when the driver has been away from the route long enough to need a new one. */
    boolean isOffRoute() {
        return offRoute >= OFF_ROUTE_COUNT;
    }

    MapEngine.NavigationState update(double latitude, double longitude) {
        final java.util.List<double[]> points = route.points;
        final MapEngine.NavigationState state = new MapEngine.NavigationState();
        state.route = route;
        if (points.size() < 2) {
            state.arrived = true;
            return state;
        }
        // The nearest point on the segments around the last match.
        double best = Double.MAX_VALUE;
        int bestSegment = index;
        double bestFraction = 0;
        final int from = Math.max(0, index - SEARCH_BACK);
        final int to = Math.min(points.size() - 2, index + SEARCH_AHEAD);
        final double cos = Math.cos(Math.toRadians(latitude));
        for (int i = from; i <= to; i++) {
            final double[] a = points.get(i);
            final double[] b = points.get(i + 1);
            // A flat projection is exact enough over one segment.
            final double ax = a[1] * cos, ay = a[0], bx = b[1] * cos, by = b[0], px = longitude * cos, py = latitude;
            final double dx = bx - ax, dy = by - ay;
            final double length = dx * dx + dy * dy;
            double t = length > 0 ? ((px - ax) * dx + (py - ay) * dy) / length : 0;
            t = Math.max(0, Math.min(1, t));
            final double d = MapEngine.distance(latitude, longitude, ay + t * dy, (ax + t * dx) / cos);
            if (d < best) {
                best = d;
                bestSegment = i;
                bestFraction = t;
            }
        }
        offRoute = best > OFF_ROUTE_METERS ? offRoute + 1 : 0;
        index = bestSegment;
        final float segmentLength = route.along[bestSegment + 1] - route.along[bestSegment];
        final float travelled = route.along[bestSegment] + (float) (segmentLength * bestFraction);
        final float total = route.along[route.along.length - 1];
        state.remainingMeters = Math.max(0, total - travelled);
        state.progress = total > 0 ? Math.min(1, travelled / total) : 1;
        state.remainingSeconds = total > 0 ? route.seconds * state.remainingMeters / total : 0;
        final double[] end = points.get(points.size() - 1);
        state.arrived = MapEngine.distance(latitude, longitude, end[0], end[1]) < ARRIVED_METERS
                || state.remainingMeters < ARRIVED_METERS;
        // The next turn is the first one ahead of the driver.
        for (int i = 0; i < route.maneuvers.size(); i++) {
            final MapEngine.Maneuver maneuver = route.maneuvers.get(i);
            if (maneuver.distance > travelled + 5) {
                state.next = maneuver;
                state.toNext = maneuver.distance - travelled;
                state.then = i + 1 < route.maneuvers.size() ? route.maneuvers.get(i + 1) : null;
                break;
            }
        }
        return state;
    }
}
