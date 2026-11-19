package org.telegram.cogram;

import java.util.List;

/**
 * Where the assistant's replies come from. {@link AssistantBot} keeps the conversations and shows a reply in
 * Telegram's chat as it arrives; a source only turns a conversation into the reply's text, piece by piece.
 *
 * Today the only source is {@link SampleReplySource}. The organization's AI server becomes a second source that
 * reads the proxy's server-sent events, and {@link #forAccount} picks it once the app knows the server's address
 * and the user's token. Nothing else in the assistant changes when it does.
 */
interface AssistantReplySource {

    /** One message of a conversation, oldest first, as the AI service needs it. */
    final class Turn {
        final boolean fromUser;
        final String text;

        Turn(boolean fromUser, String text) {
            this.fromUser = fromUser;
            this.text = text;
        }
    }

    /**
     * Receives a reply as it is written. Calls may come from any thread; after {@link #onDone} or {@link #onError}
     * no further calls come, and none come after the reply is cancelled.
     */
    interface Listener {
        /** More of the reply's text, added to what came before. */
        void onText(String more);

        void onDone();

        /** The reply failed; {@code message} is for the log, the user sees Phogram's own text. */
        void onError(String message);
    }

    /** A reply on its way; Telegram's stop button cancels it. */
    interface Call {
        void cancel();
    }

    /** Starts writing the reply to {@code conversation}, whose last turn is the user's new message. */
    Call reply(int topicId, List<Turn> conversation, Listener listener);

    /** The source for an account's assistant. */
    static AssistantReplySource forAccount(int account) {
        return new SampleReplySource();
    }
}
