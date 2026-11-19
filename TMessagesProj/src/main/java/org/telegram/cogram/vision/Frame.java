package org.telegram.cogram.vision;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/**
 * Phogram: an image handed to a detector, in place of Google's Mobile Vision library, which Phogram does not ship.
 * Its detectors never work (see BarcodeDetector and FaceDetector), so Telegram falls back to zxing for QR codes
 * and does without face detection.
 */
public final class Frame {
    public static final int ROTATION_0 = 0;
    public static final int ROTATION_90 = 1;
    public static final int ROTATION_180 = 2;
    public static final int ROTATION_270 = 3;

    private Frame() {
    }

    public static final class Builder {
        public Builder setBitmap(Bitmap bitmap) {
            return this;
        }

        public Builder setImageData(ByteBuffer data, int width, int height, int format) {
            return this;
        }

        public Builder setRotation(int rotation) {
            return this;
        }

        public Frame build() {
            return new Frame();
        }
    }
}
