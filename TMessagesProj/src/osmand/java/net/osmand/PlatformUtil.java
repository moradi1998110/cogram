package net.osmand;

import net.osmand.map.OsmandRegions;

import org.apache.commons.logging.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

/**
 * OsmAnd's core library asks this class for its logs and XML parsers. Its own copy, for desktop Java, is left out of
 * the library's Android jar, as OsmAnd's app brings an Android one; Phogram uses the core library without OsmAnd's
 * app, so this is that Android copy, with the same methods. Logs go to Android's log, warnings and errors only.
 */
public class PlatformUtil {

    private static final String TAG = "net.osmand";

    private static OsmandRegions osmandRegions;

    public static Log getLog(Class<?> cl) {
        return new AndroidLog(cl.getSimpleName());
    }

    public static void setOsmandRegions(OsmandRegions or) {
        osmandRegions = or;
    }

    public static OsmandRegions getOsmandRegions() throws IOException {
        if (osmandRegions == null) {
            osmandRegions = new OsmandRegions(null);
        }
        return osmandRegions;
    }

    public static XmlPullParser newXMLPullParser() throws XmlPullParserException {
        final org.kxml2.io.KXmlParser xmlParser = new org.kxml2.io.KXmlParser();
        xmlParser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        return xmlParser;
    }

    public static XmlSerializer newSerializer() {
        return new org.kxml2.io.KXmlSerializer();
    }

    private static final class AndroidLog implements Log {

        private final String name;

        AndroidLog(String name) {
            this.name = name;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public boolean isFatalEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public void trace(Object message) {
        }

        @Override
        public void trace(Object message, Throwable t) {
        }

        @Override
        public void debug(Object message) {
        }

        @Override
        public void debug(Object message, Throwable t) {
        }

        @Override
        public void info(Object message) {
        }

        @Override
        public void info(Object message, Throwable t) {
        }

        @Override
        public void warn(Object message) {
            android.util.Log.w(TAG, name + " " + message);
        }

        @Override
        public void warn(Object message, Throwable t) {
            android.util.Log.w(TAG, name + " " + message, t);
        }

        @Override
        public void error(Object message) {
            android.util.Log.e(TAG, name + " " + message);
        }

        @Override
        public void error(Object message, Throwable t) {
            android.util.Log.e(TAG, name + " " + message, t);
        }

        @Override
        public void fatal(Object message) {
            android.util.Log.e(TAG, name + " " + message);
        }

        @Override
        public void fatal(Object message, Throwable t) {
            android.util.Log.e(TAG, name + " " + message, t);
        }
    }
}
