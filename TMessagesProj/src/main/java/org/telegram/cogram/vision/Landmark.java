package org.telegram.cogram.vision;

import android.graphics.PointF;

/** Phogram: a point on a face, in place of Google's Mobile Vision class (see Frame). None is ever found. */
public final class Landmark {
    public static final int BOTTOM_MOUTH = 0;
    public static final int LEFT_CHEEK = 1;
    public static final int LEFT_EAR_TIP = 2;
    public static final int LEFT_EAR = 3;
    public static final int LEFT_EYE = 4;
    public static final int LEFT_MOUTH = 5;
    public static final int NOSE_BASE = 6;
    public static final int RIGHT_CHEEK = 7;
    public static final int RIGHT_EAR_TIP = 8;
    public static final int RIGHT_EAR = 9;
    public static final int RIGHT_EYE = 10;
    public static final int RIGHT_MOUTH = 11;

    private Landmark() {
    }

    public int getType() {
        return 0;
    }

    public PointF getPosition() {
        return new PointF();
    }
}
