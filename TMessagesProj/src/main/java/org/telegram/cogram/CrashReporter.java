package org.telegram.cogram;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import org.telegram.messenger.BuildVars;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the reason for the last crash where {@link CrashReportActivity} can read it, because the
 * app is tested on phones without adb. The activity runs in its own process so it still opens when
 * the main process crashes on every start.
 */
public final class CrashReporter {

    private static final String REPORT_PROCESS_SUFFIX = ":report";
    private static final String CRASH_FILE = "cogram_crash.txt";
    private static final int MAX_CRASH_CHARS = 8000;
    private static final int MAX_EXIT_INFOS = 5;
    private static final int MAX_TRACE_BYTES = 512 * 1024;
    private static final int MAX_TRACE_CHARS = 20000;
    private static final int MIN_TRACE_RUN = 6;
    private static final String LOGCAT_LINES = "800";

    private static Boolean reportProcess;

    private CrashReporter() {
    }

    public static synchronized boolean isReportProcess() {
        if (reportProcess == null) {
            reportProcess = readProcessName().endsWith(REPORT_PROCESS_SUFFIX);
        }
        return reportProcess;
    }

    private static String readProcessName() {
        byte[] buffer = new byte[256];
        try (FileInputStream stream = new FileInputStream("/proc/self/cmdline")) {
            int length = Math.max(stream.read(buffer), 0);
            int end = 0;
            while (end < length && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static void install(Context context) {
        // BuildVars sets its own handler when it loads, and that one must not replace this one.
        final boolean logsEnabled = BuildVars.LOGS_ENABLED;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            try {
                saveCrash(context, thread, error, logsEnabled);
                Intent intent = new Intent(context, CrashReportActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(intent);
            } catch (Throwable ignore) {

            }
            Process.killProcess(Process.myPid());
            System.exit(10);
        });
    }

    private static void saveCrash(Context context, Thread thread, Throwable error, boolean logsEnabled) throws Exception {
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(new File(context.getFilesDir(), CRASH_FILE)))) {
            writer.println("Time: " + formatTime(System.currentTimeMillis()));
            writer.println("Thread: " + thread.getName() + ", logs enabled: " + logsEnabled);
            error.printStackTrace(writer);
        }
    }

    private static String formatTime(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(time));
    }

    static String buildReport(Context context) {
        StringBuilder report = new StringBuilder();
        appendDevice(context, report);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            report.append("\n== Process exits ==\n");
            appendExitInfos(context, report);
        }
        report.append("\n== Last Java crash ==\n");
        appendCrashFile(context, report);
        report.append("\n== Logcat ==\n");
        appendLogcat(report);
        return report.toString();
    }

    private static void appendDevice(Context context, StringBuilder report) {
        report.append("Now: ").append(formatTime(System.currentTimeMillis())).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            report.append("App: ").append(context.getPackageName()).append(' ').append(info.versionName).append('\n');
        } catch (Exception e) {
            report.append("App: ").append(e).append('\n');
        }
    }

    private static void appendCrashFile(Context context, StringBuilder report) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) {
            report.append("none\n");
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            // A stack overflow writes thousands of lines, which would push everything after it out of the report.
            int limit = report.length() + MAX_CRASH_CHARS;
            String line;
            while (report.length() < limit && (line = reader.readLine()) != null) {
                report.append(line).append('\n');
            }
        } catch (Exception e) {
            report.append(e).append('\n');
        }
    }

    private static void appendExitInfos(Context context, StringBuilder report) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        try {
            List<ApplicationExitInfo> infos = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, MAX_EXIT_INFOS);
            for (ApplicationExitInfo info : infos) {
                report.append(formatTime(info.getTimestamp()))
                        .append(' ').append(info.getProcessName())
                        .append(" reason=").append(exitReasonName(info.getReason()))
                        .append(" status=").append(info.getStatus())
                        .append(" description=").append(info.getDescription()).append('\n');
            }
            // Only the newest native crash or freeze is worth the space.
            for (ApplicationExitInfo info : infos) {
                int reason = info.getReason();
                if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR) {
                    report.append("\n== Trace of ").append(formatTime(info.getTimestamp())).append(" ==\n");
                    appendTrace(info, report);
                    break;
                }
            }
        } catch (Exception e) {
            report.append(e).append('\n');
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH:
                return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER:
                return "OTHER";
            default:
                return String.valueOf(reason);
        }
    }

    /** Native traces are binary tombstones on recent Android, so this keeps their readable text. */
    private static void appendTrace(ApplicationExitInfo info, StringBuilder report) throws Exception {
        try (InputStream stream = info.getTraceInputStream()) {
            if (stream == null) {
                report.append("none\n");
                return;
            }
            StringBuilder trace = new StringBuilder();
            StringBuilder run = new StringBuilder();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while (total < MAX_TRACE_BYTES && (read = stream.read(buffer)) > 0) {
                total += read;
                for (int a = 0; a < read; a++) {
                    int value = buffer[a];
                    if (value >= 0x20 && value < 0x7f || value == '\t') {
                        run.append((char) value);
                    } else {
                        if (run.length() >= MIN_TRACE_RUN) {
                            trace.append(run).append('\n');
                        }
                        run.setLength(0);
                    }
                }
            }
            if (run.length() >= MIN_TRACE_RUN) {
                trace.append(run).append('\n');
            }
            report.append(trace, 0, Math.min(trace.length(), MAX_TRACE_CHARS));
        }
    }

    private static void appendLogcat(StringBuilder report) {
        try {
            java.lang.Process logcat = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", LOGCAT_LINES, "-v", "time"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    report.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            report.append(e).append('\n');
        }
    }
}
