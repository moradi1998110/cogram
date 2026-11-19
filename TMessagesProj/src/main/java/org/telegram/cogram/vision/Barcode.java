package org.telegram.cogram.vision;

import android.graphics.Point;

/** Phogram: a code a detector found, in place of Google's Mobile Vision class (see Frame). None is ever found. */
public final class Barcode {
    public static final int TEXT = 7;
    public static final int DRIVER_LICENSE = 12;
    public static final int QR_CODE = 256;
    public static final int PDF417 = 2048;

    public String rawValue;
    public String displayValue;
    public int format;
    public int valueFormat;
    public Point[] cornerPoints;
}
