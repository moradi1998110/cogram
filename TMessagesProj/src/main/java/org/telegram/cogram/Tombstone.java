package org.telegram.cogram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the crashing thread out of an Android tombstone, which ApplicationExitInfo hands over as
 * protobuf. Field numbers follow system/core/debuggerd/proto/tombstone.proto.
 */
final class Tombstone {

    private static final int TOMBSTONE_TID = 6;
    private static final int TOMBSTONE_SIGNAL = 10;
    private static final int TOMBSTONE_ABORT_MESSAGE = 14;
    private static final int TOMBSTONE_CAUSES = 15;
    private static final int TOMBSTONE_THREADS = 16;
    private static final int SIGNAL_NAME = 2;
    private static final int SIGNAL_CODE_NAME = 4;
    private static final int SIGNAL_FAULT_ADDRESS = 9;
    private static final int CAUSE_HUMAN_READABLE = 1;
    private static final int MAP_KEY = 1;
    private static final int MAP_VALUE = 2;
    private static final int THREAD_NAME = 2;
    private static final int THREAD_BACKTRACE = 4;
    private static final int FRAME_REL_PC = 1;
    private static final int FRAME_FUNCTION_NAME = 4;
    private static final int FRAME_FUNCTION_OFFSET = 5;
    private static final int FRAME_FILE_NAME = 6;
    private static final int MAX_FRAMES = 40;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH = 2;
    private static final int WIRE_FIXED32 = 5;

    private Tombstone() {}

    /** One field of a protobuf message: its number, and either a varint or a byte range. */
    private static final class Field {
        int number;
        long varint;
        int start;
        int end;
    }

    private static List<Field> fields(byte[] data, int start, int end) {
        ArrayList<Field> fields = new ArrayList<>();
        int[] position = {start};
        while (position[0] < end) {
            long key = readVarint(data, position);
            Field field = new Field();
            field.number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == WIRE_VARINT) {
                field.varint = readVarint(data, position);
            } else if (wire == WIRE_LENGTH) {
                int length = (int) readVarint(data, position);
                field.start = position[0];
                field.end = position[0] + length;
                position[0] = field.end;
            } else if (wire == WIRE_FIXED64) {
                position[0] += 8;
            } else if (wire == WIRE_FIXED32) {
                position[0] += 4;
            } else {
                throw new IllegalArgumentException("wire type " + wire);
            }
            fields.add(field);
        }
        return fields;
    }

    private static long readVarint(byte[] data, int[] position) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            byte b = data[position[0]++];
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("varint too long");
    }

    private static String text(byte[] data, Field field) {
        return new String(data, field.start, field.end - field.start, StandardCharsets.UTF_8);
    }

    static String describeCrash(byte[] data) {
        StringBuilder out = new StringBuilder();
        long tid = -1;
        Field crashedThread = null;
        List<Field> top = fields(data, 0, data.length);
        for (Field field : top) {
            if (field.number == TOMBSTONE_TID) {
                tid = field.varint;
            }
        }
        for (Field field : top) {
            if (field.number == TOMBSTONE_SIGNAL) {
                for (Field signal : fields(data, field.start, field.end)) {
                    if (signal.number == SIGNAL_NAME || signal.number == SIGNAL_CODE_NAME) {
                        out.append(text(data, signal)).append(' ');
                    } else if (signal.number == SIGNAL_FAULT_ADDRESS) {
                        out.append("fault addr 0x").append(Long.toHexString(signal.varint)).append(' ');
                    }
                }
                out.append('\n');
            } else if (field.number == TOMBSTONE_ABORT_MESSAGE) {
                out.append("Abort message: ").append(text(data, field)).append('\n');
            } else if (field.number == TOMBSTONE_CAUSES) {
                for (Field cause : fields(data, field.start, field.end)) {
                    if (cause.number == CAUSE_HUMAN_READABLE) {
                        out.append("Cause: ").append(text(data, cause)).append('\n');
                    }
                }
            } else if (field.number == TOMBSTONE_THREADS) {
                long key = -1;
                Field value = null;
                for (Field entry : fields(data, field.start, field.end)) {
                    if (entry.number == MAP_KEY) {
                        key = entry.varint;
                    } else if (entry.number == MAP_VALUE) {
                        value = entry;
                    }
                }
                if (key == tid) {
                    crashedThread = value;
                }
            }
        }
        out.append("\n== Crashing thread ").append(tid).append(" ==\n");
        if (crashedThread == null) {
            out.append("not found\n");
            return out.toString();
        }
        int frame = 0;
        for (Field field : fields(data, crashedThread.start, crashedThread.end)) {
            if (field.number == THREAD_NAME) {
                out.append("name: ").append(text(data, field)).append('\n');
            } else if (field.number == THREAD_BACKTRACE && frame < MAX_FRAMES) {
                out.append(frameLine(data, field, frame++)).append('\n');
            }
        }
        return out.toString();
    }

    private static String frameLine(byte[] data, Field frame, int index) {
        long relPc = 0;
        long functionOffset = 0;
        String function = "";
        String file = "";
        for (Field field : fields(data, frame.start, frame.end)) {
            if (field.number == FRAME_REL_PC) {
                relPc = field.varint;
            } else if (field.number == FRAME_FUNCTION_NAME) {
                function = text(data, field);
            } else if (field.number == FRAME_FUNCTION_OFFSET) {
                functionOffset = field.varint;
            } else if (field.number == FRAME_FILE_NAME) {
                file = text(data, field);
            }
        }
        String line = String.format(Locale.US, "#%02d pc %016x %s", index, relPc, file.substring(file.lastIndexOf('/') + 1));
        return function.isEmpty() ? line : line + " (" + function + "+" + functionOffset + ")";
    }
}
