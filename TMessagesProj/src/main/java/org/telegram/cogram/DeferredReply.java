package org.telegram.cogram;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Stands in for a response that only exists once the engine has really sent the message. */
final class DeferredReply extends TLObject {

    private CogramGateway.Reply reply;
    private TLObject response;
    private TLRPC.TL_error error;
    private boolean done;

    /** Telegram resends a message after a restart, and the latest request is the one still listening. */
    void attach(CogramGateway.Reply reply) {
        synchronized (this) {
            if (!done) {
                this.reply = reply;
                return;
            }
        }
        reply.run(response, error);
    }

    void complete(TLObject response, TLRPC.TL_error error) {
        CogramGateway.Reply target;
        synchronized (this) {
            this.response = response;
            this.error = error;
            done = true;
            target = reply;
        }
        if (target != null) {
            target.run(response, error);
        }
    }
}
