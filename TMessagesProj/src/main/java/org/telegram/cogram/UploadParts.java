package org.telegram.cogram;

import org.telegram.tgnet.NativeByteBuffer;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Collects the parts of a file Telegram uploads, which may arrive out of order, until a request uses it. */
final class UploadParts {

    private final Map<Long, TreeMap<Integer, byte[]>> files = new HashMap<>();

    synchronized void savePart(long fileId, int part, NativeByteBuffer bytes) {
        byte[] copy = new byte[bytes.limit()];
        bytes.buffer.position(0);
        bytes.buffer.get(copy);
        TreeMap<Integer, byte[]> parts = files.get(fileId);
        if (parts == null) {
            parts = new TreeMap<>();
            files.put(fileId, parts);
        }
        parts.put(part, copy);
    }

    /** Returns the whole file and forgets it, or null when no part of it arrived. */
    synchronized byte[] take(long fileId) {
        TreeMap<Integer, byte[]> parts = files.remove(fileId);
        if (parts == null) {
            return null;
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        for (byte[] part : parts.values()) {
            file.write(part, 0, part.length);
        }
        return file.toByteArray();
    }
}
