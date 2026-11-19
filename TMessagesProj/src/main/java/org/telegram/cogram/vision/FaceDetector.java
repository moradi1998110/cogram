package org.telegram.cogram.vision;

import android.content.Context;
import android.util.SparseArray;

/**
 * Phogram: in place of Google's face detector (see Frame). It is never operational, so Telegram's photo editor
 * places masks where the user puts them instead of on detected faces.
 */
public final class FaceDetector {
    public static final int FAST_MODE = 0;
    public static final int ACCURATE_MODE = 1;
    public static final int NO_LANDMARKS = 0;
    public static final int ALL_LANDMARKS = 1;
    public static final int NO_CLASSIFICATIONS = 0;
    public static final int ALL_CLASSIFICATIONS = 1;

    private FaceDetector() {
    }

    public boolean isOperational() {
        return false;
    }

    public SparseArray<Face> detect(Frame frame) {
        return new SparseArray<>();
    }

    public void release() {
    }

    public static final class Builder {
        public Builder(Context context) {
        }

        public Builder setMode(int mode) {
            return this;
        }

        public Builder setLandmarkType(int type) {
            return this;
        }

        public Builder setClassificationType(int type) {
            return this;
        }

        public Builder setTrackingEnabled(boolean enabled) {
            return this;
        }

        public Builder setProminentFaceOnly(boolean only) {
            return this;
        }

        public Builder setMinFaceSize(float size) {
            return this;
        }

        public FaceDetector build() {
            return new FaceDetector();
        }
    }
}
