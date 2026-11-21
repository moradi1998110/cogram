package net.osmand.router;

import net.osmand.router.HHRouteDataStructure.HHRoutingConfig;
import net.osmand.router.HHRouteDataStructure.HHRoutingContext;
import net.osmand.router.HHRouteDataStructure.NetworkDBPoint;

/**
 * Keeps OsmAnd's highway hierarchy network (its "HH" routing) loaded between routes. OsmAnd reuses a loaded network
 * only for the same routing context, while the app builds a new context for each route; so the kept network is handed
 * the new context before each run. In this package because the context's field is package-private.
 */
public final class PhogramHHCache {

    private static HHRoutingContext<NetworkDBPoint> kept;
    private static Object keptFor;

    private PhogramHHCache() {
    }

    /** Gives this run the network kept for the same file and routing rules, and keeps the network it loads. */
    public static synchronized void use(HHRoutingConfig config, RoutingContext context, Object owner) {
        if (kept != null && keptFor == owner) {
            kept.rctx = context;
            config.cacheContext(kept);
        } else {
            config.cacheContext(null);
        }
    }

    /** After a run: keeps the network it used. */
    public static synchronized void keep(HHRoutingConfig config, Object owner) {
        if (config.cacheCtx != null) {
            kept = config.cacheCtx;
            keptFor = owner;
        }
    }

    public static synchronized void clear() {
        kept = null;
        keptFor = null;
    }
}
