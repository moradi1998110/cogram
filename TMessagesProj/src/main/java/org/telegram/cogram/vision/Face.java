package org.telegram.cogram.vision;

import java.util.Collections;
import java.util.List;

/** Phogram: a face a detector found, in place of Google's Mobile Vision class (see Frame). None is ever found. */
public final class Face {
    private Face() {
    }

    public List<Landmark> getLandmarks() {
        return Collections.emptyList();
    }
}
