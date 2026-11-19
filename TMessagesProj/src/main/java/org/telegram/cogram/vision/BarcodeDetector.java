package org.telegram.cogram.vision;

import android.content.Context;
import android.util.SparseArray;

/**
 * Phogram: in place of Google's barcode detector (see Frame). It is never operational, which tells Telegram's
 * scanners to read QR codes with zxing, a library that runs on the phone alone.
 */
public final class BarcodeDetector {

    private BarcodeDetector() {
    }

    public boolean isOperational() {
        return false;
    }

    public SparseArray<Barcode> detect(Frame frame) {
        return new SparseArray<>();
    }

    public void release() {
    }

    public static final class Builder {
        public Builder(Context context) {
        }

        public Builder setBarcodeFormats(int formats) {
            return this;
        }

        public BarcodeDetector build() {
            return new BarcodeDetector();
        }
    }
}
