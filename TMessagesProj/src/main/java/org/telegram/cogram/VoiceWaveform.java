package org.telegram.cogram;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Makes the waveform of a voice message. XMPP carries no waveform, and Telegram can read one only
 * from Opus, while Conversations records AAC, so other formats are decoded here and handed to the
 * function Telegram itself uses while recording.
 */
public final class VoiceWaveform {

    private static final long CODEC_TIMEOUT_US = 10000;
    private static final long DECODE_TIMEOUT_MS = 30000;
    // The waveform keeps only peaks, so samples are reduced to peaks before they fill memory.
    private static final int SAMPLES_PER_PEAK = 64;
    private static final int INITIAL_PEAKS = 1 << 14;

    private VoiceWaveform() {
    }

    public static byte[] generate(String path) {
        byte[] opus = MediaController.getWaveform(path);
        if (opus != null) {
            return opus;
        }
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        // Telegram loses a waveform whenever it reloads the message, and decoding again would be wasted.
        File directory = new File(ApplicationLoader.applicationContext.getCacheDir(), "cogram_waveforms");
        File cached = new File(directory, file.getName() + "_" + file.length());
        try {
            if (cached.isFile()) {
                byte[] bytes = new byte[(int) cached.length()];
                try (FileInputStream in = new FileInputStream(cached)) {
                    return in.read(bytes) == bytes.length ? bytes : null;
                }
            }
            byte[] waveform = decode(path);
            if (waveform != null && (directory.isDirectory() || directory.mkdirs())) {
                try (FileOutputStream out = new FileOutputStream(cached)) {
                    out.write(waveform);
                }
            }
            return waveform;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] decode(String path) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            MediaFormat format = null;
            for (int track = 0; track < extractor.getTrackCount() && format == null; track++) {
                MediaFormat candidate = extractor.getTrackFormat(track);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    format = candidate;
                    extractor.selectTrack(track);
                }
            }
            if (format == null) {
                return null;
            }
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            short[] peaks = new short[INITIAL_PEAKS];
            int count = 0;
            int samples = 0;
            int peak = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS;
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (input >= 0) {
                        int size = extractor.readSampleData(codec.getInputBuffer(input), 0);
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int output = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (output < 0) {
                    continue;
                }
                ByteBuffer buffer = codec.getOutputBuffer(output);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    ShortBuffer pcm = buffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer();
                    while (pcm.hasRemaining()) {
                        peak = Math.max(peak, Math.abs((int) pcm.get()));
                        if (++samples == SAMPLES_PER_PEAK) {
                            if (count == peaks.length) {
                                peaks = Arrays.copyOf(peaks, peaks.length * 2);
                            }
                            peaks[count++] = (short) Math.min(peak, Short.MAX_VALUE);
                            samples = 0;
                            peak = 0;
                        }
                    }
                }
                codec.releaseOutputBuffer(output, false);
                outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            }
            return outputDone && count > 0 ? MediaController.getInstance().getWaveform2(peaks, count) : null;
        } finally {
            if (codec != null) {
                codec.release();
            }
            extractor.release();
        }
    }
}
