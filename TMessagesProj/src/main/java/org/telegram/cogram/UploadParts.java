package org.telegram.cogram;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Collects the parts of a file Telegram uploads, which may arrive out of order, until a request uses
 * it. Parts go to disk, since a video or a long voice message would not fit in memory.
 */
final class UploadParts {

    // An upload no request used within this time was abandoned, and its parts would never be freed.
    private static final long ABANDONED_AFTER_MS = 24 * 60 * 60 * 1000L;
    private static final int COPY_BUFFER = 64 * 1024;

    private final File root = new File(ApplicationLoader.applicationContext.getCacheDir(), "cogram_uploads");

    synchronized void savePart(long fileId, int part, NativeByteBuffer bytes) {
        removeAbandoned();
        File directory = new File(root, Long.toString(fileId));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            FileLog.e("cogram: could not create " + directory);
            return;
        }
        byte[] copy = new byte[bytes.limit()];
        bytes.buffer.position(0);
        bytes.buffer.get(copy);
        try (OutputStream out = new FileOutputStream(new File(directory, Integer.toString(part)))) {
            out.write(copy);
        } catch (IOException e) {
            FileLog.e(e);
        }
    }

    /** Returns the whole file and forgets it, or null when no part of it arrived. */
    synchronized byte[] take(long fileId) {
        File file = takeFile(fileId);
        if (file == null) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            copy(in, out);
            return out.toByteArray();
        } catch (IOException e) {
            FileLog.e(e);
            return null;
        } finally {
            file.delete();
        }
    }

    /** Joins the parts into one file the caller owns and deletes, or returns null when none arrived. */
    synchronized File takeFile(long fileId) {
        File directory = new File(root, Long.toString(fileId));
        File[] parts = directory.listFiles();
        if (parts == null || parts.length == 0) {
            return null;
        }
        Arrays.sort(parts, (a, b) -> Integer.compare(Integer.parseInt(a.getName()), Integer.parseInt(b.getName())));
        File joined = new File(root, fileId + ".joined");
        try (OutputStream out = new FileOutputStream(joined)) {
            for (File part : parts) {
                try (InputStream in = new FileInputStream(part)) {
                    copy(in, out);
                }
            }
        } catch (IOException e) {
            FileLog.e(e);
            joined.delete();
            joined = null;
        }
        delete(directory);
        return joined;
    }

    private void removeAbandoned() {
        File[] uploads = root.listFiles();
        if (uploads == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File upload : uploads) {
            if (now - upload.lastModified() > ABANDONED_AFTER_MS) {
                delete(upload);
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
        file.delete();
    }
}
