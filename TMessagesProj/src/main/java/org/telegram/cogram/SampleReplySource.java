package org.telegram.cogram;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A reply with no AI behind it: a fixed text that repeats the user's message, written out a few characters at a
 * time so the chat screen shows a reply arriving the way the AI server's replies will.
 */
final class SampleReplySource implements AssistantReplySource {

    private static final int FIRST_STEP_MS = 400;
    private static final int STEP_MS = 60;
    private static final int STEP_CHARS = 4;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cogramAssistantSample");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Call reply(int topicId, List<Turn> conversation, Listener listener) {
        String prompt = conversation.isEmpty() ? "" : conversation.get(conversation.size() - 1).text;
        String text = LocaleController.formatString(R.string.PhogramAssistantSampleReply, prompt == null ? "" : prompt.trim());
        Writer writer = new Writer(text, listener);
        writer.future = TIMER.scheduleWithFixedDelay(writer, FIRST_STEP_MS, STEP_MS, TimeUnit.MILLISECONDS);
        return writer;
    }

    private static final class Writer implements Runnable, Call {
        private final String text;
        private final Listener listener;
        private int shown;
        private boolean over;
        volatile ScheduledFuture<?> future;

        Writer(String text, Listener listener) {
            this.text = text;
            this.listener = listener;
        }

        @Override
        public void run() {
            String more;
            boolean done;
            synchronized (this) {
                if (over) return;
                int next = Math.min(text.length(), shown + STEP_CHARS);
                more = text.substring(shown, next);
                shown = next;
                done = shown >= text.length();
                if (done) {
                    over = true;
                    future.cancel(false);
                }
            }
            if (!more.isEmpty()) listener.onText(more);
            if (done) listener.onDone();
        }

        @Override
        public synchronized void cancel() {
            over = true;
            if (future != null) future.cancel(false);
        }
    }
}
