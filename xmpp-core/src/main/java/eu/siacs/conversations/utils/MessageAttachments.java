package eu.siacs.conversations.utils;

import eu.siacs.conversations.entities.Message;

/**
 * Phogram: messages attached to an earlier one (XEP-0367, Message Attaching), used for the caption of a photo or a
 * file. A caption goes out as a text message of its own right after the file, encrypted as any text is, with
 * {@code <attach-to id="the file message's id"/>}; clients without the extension show it as the next message, and
 * Phogram shows it under the file. The engine only carries the reference; the app keeps it.
 */
public final class MessageAttachments {

    public static final String NAMESPACE = "urn:xmpp:message-attaching:1";

    public interface Listener {
        /** A received or carbon-copied message attached to the message with this id (its stanza or origin id). */
        void onAttached(Message message, String targetId);
    }

    private static volatile Listener listener;

    private MessageAttachments() {}

    public static void setListener(final Listener value) {
        listener = value;
    }

    public static void attached(final Message message, final String targetId) {
        final Listener current = listener;
        if (current != null && targetId != null && !targetId.isEmpty()) {
            current.onAttached(message, targetId);
        }
    }
}
