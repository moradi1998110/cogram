package org.telegram.cogram;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_update;
import org.webrtc.VideoSink;

import java.lang.ref.WeakReference;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.ExternalCallUi;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import eu.siacs.conversations.xmpp.manager.JingleManager;

/**
 * Carries one-to-one calls between Telegram's call service and the engine's Jingle calls. Telegram's
 * phone requests become Jingle actions, and Jingle states come back as Telegram call updates, so
 * Telegram's call screen, ringing and notifications work unchanged.
 */
final class XmppCalls implements XmppConnectionService.OnJingleRtpConnectionUpdate {

    interface Peers {
        long peerId(Jid address);

        String address(long peerId);

        void push(TLRPC.Updates updates, long peerId);
    }

    private static final class Call {
        final long id;
        final Jid with;
        final long peerId;
        final boolean outgoing;
        final boolean video;
        String sessionId;
        boolean started;
        boolean finished;
        XmppCallInstance instance;

        Call(long id, Jid with, long peerId, boolean outgoing, boolean video) {
            this.id = id;
            this.with = with;
            this.peerId = peerId;
            this.outgoing = outgoing;
            this.video = video;
        }
    }

    private final XmppConnectionService service;
    private final Account account;
    private final long selfId;
    private final Peers peers;
    private final Map<Long, Call> calls = new HashMap<>();

    XmppCalls(XmppConnectionService service, Account account, long selfId, Peers peers) {
        this.service = service;
        this.account = account;
        this.selfId = selfId;
        this.peers = peers;
        ExternalCallUi.enable(VideoCapturerDevice.getEglBase().getEglBaseContext());
        service.setOnRtpConnectionUpdateListener(this);
    }

    /** Whether the contact's client answers audio or video calls, which decides Telegram's call buttons. */
    static RtpCapability.Capability capability(Contact contact) {
        return RtpCapability.checkWithFallback(contact);
    }

    private JingleManager jingle() {
        return account.getXmppConnection().getManager(JingleManager.class);
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    private static long callId(String sessionId) {
        return (sessionId.hashCode() & 0x7fffffffL) | 0x100000000L;
    }

    private Call findBySession(String sessionId) {
        for (Call call : calls.values()) {
            if (sessionId.equals(call.sessionId)) {
                return call;
            }
        }
        return null;
    }

    private JingleRtpConnection connection(Call call) {
        if (call.sessionId == null) {
            return null;
        }
        WeakReference<JingleRtpConnection> reference = jingle().findJingleRtpConnection(call.with, call.sessionId);
        return reference == null ? null : reference.get();
    }

    synchronized TL_phone.TL_phone_phoneCall requestCall(long peerId, boolean video) {
        String address = peers.address(peerId);
        if (address == null) {
            return null;
        }
        Jid with = Jid.of(address);
        Set<Media> media = video ? EnumSet.of(Media.AUDIO, Media.VIDEO) : EnumSet.of(Media.AUDIO);
        JingleManager.RtpSessionProposal proposal = jingle().proposeJingleRtpSession(with, media);
        if (proposal == null) {
            return null;
        }
        Call call = new Call(callId(proposal.getSessionId()), with.asBareJid(), peerId, true, video);
        call.sessionId = proposal.getSessionId();
        calls.put(call.id, call);
        return phoneCall(waiting(call, 0));
    }

    synchronized TL_phone.TL_phone_phoneCall acceptCall(long callId) {
        Call call = calls.get(callId);
        JingleRtpConnection connection = call == null ? null : connection(call);
        if (connection == null) {
            return null;
        }
        connection.acceptCall();
        return phoneCall(waiting(call, now()));
    }

    synchronized boolean receivedCall(long callId) {
        return calls.containsKey(callId);
    }

    /** Ends the call in whatever stage it is: retracts our ringing, declines theirs or hangs up. */
    synchronized void discardCall(long callId, TLRPC.PhoneCallDiscardReason reason) {
        Call call = calls.get(callId);
        if (call == null) {
            return;
        }
        call.finished = true;
        JingleRtpConnection connection = connection(call);
        try {
            if (connection != null && !call.outgoing && !call.started) {
                connection.rejectCall();
            } else if (connection != null) {
                connection.endCall();
            } else if (call.outgoing) {
                jingle().retractSessionProposal(call.with);
            }
        } catch (IllegalStateException e) {
            // The call may have ended on the other side at the same moment.
            FileLog.e(e);
        }
        calls.remove(callId);
    }

    synchronized XmppCallInstance createInstance(long callId, VideoSink remoteSink) {
        Call call = calls.get(callId);
        JingleRtpConnection connection = call == null ? null : connection(call);
        if (connection == null) {
            return null;
        }
        call.instance = new XmppCallInstance(connection, remoteSink);
        if (connection.getEndUserState() == RtpEndUserState.CONNECTED) {
            call.instance.onConnected();
        }
        return call.instance;
    }

    @Override
    public void onJingleRtpConnectionUpdate(Account account, Jid with, String sessionId, RtpEndUserState state) {
        if (account != this.account) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("cogram: call " + sessionId + " with " + with + " is " + state);
        }
        TL_phone.PhoneCall update = null;
        long peerId;
        synchronized (this) {
            Call call = findBySession(sessionId);
            if (call == null) {
                if (state != RtpEndUserState.INCOMING_CALL) {
                    return;
                }
                call = incomingCall(with, sessionId);
                if (call == null) {
                    return;
                }
                update = requested(call);
            } else if (call.finished) {
                return;
            } else {
                update = onState(call, state);
            }
            peerId = call.peerId;
        }
        if (update != null) {
            TL_update.TL_updatePhoneCall phoneCallUpdate = new TL_update.TL_updatePhoneCall();
            phoneCallUpdate.phone_call = update;
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.updates.add(phoneCallUpdate);
            updates.date = now();
            peers.push(updates, peerId);
        }
    }

    @Override
    public void onAudioDeviceChanged(CallIntegration.AudioDevice selectedAudioDevice, Set<CallIntegration.AudioDevice> availableAudioDevices) {
    }

    private Call incomingCall(Jid with, String sessionId) {
        WeakReference<JingleRtpConnection> reference = jingle().findJingleRtpConnection(with, sessionId);
        JingleRtpConnection connection = reference == null ? null : reference.get();
        if (connection == null) {
            return null;
        }
        boolean video = connection.getMedia().contains(Media.VIDEO);
        Call call = new Call(callId(sessionId), with.asBareJid(), peers.peerId(with.asBareJid()), false, video);
        call.sessionId = sessionId;
        calls.put(call.id, call);
        return call;
    }

    /** Turns a Jingle state into the Telegram call update that moves Telegram's call service along. */
    private TL_phone.PhoneCall onState(Call call, RtpEndUserState state) {
        switch (state) {
            case RINGING:
                return call.outgoing ? waiting(call, now()) : null;
            case CONNECTING:
            case ACCEPTING_CALL:
                return start(call);
            case CONNECTED:
                TL_phone.PhoneCall started = start(call);
                if (call.instance != null) {
                    call.instance.onConnected();
                }
                return started;
            case RECONNECTING:
                if (call.instance != null) {
                    call.instance.onReconnecting();
                }
                return null;
            case DECLINED_OR_BUSY:
                return discarded(call, new TLRPC.TL_phoneCallDiscardReasonBusy());
            case RETRACTED:
                return discarded(call, new TLRPC.TL_phoneCallDiscardReasonMissed());
            case ENDED:
            case ENDING_CALL:
                return discarded(call, new TLRPC.TL_phoneCallDiscardReasonHangup());
            case CONTACT_OFFLINE:
            case CONNECTIVITY_ERROR:
            case CONNECTIVITY_LOST_ERROR:
            case NO_INTERNET:
            case APPLICATION_ERROR:
            case SECURITY_ERROR:
                return discarded(call, new TLRPC.TL_phoneCallDiscardReasonDisconnect());
            default:
                return null;
        }
    }

    /** The first sign that media is being negotiated tells Telegram to create its call engine. */
    private TL_phone.PhoneCall start(Call call) {
        if (call.started || connection(call) == null) {
            return null;
        }
        call.started = true;
        TL_phone.TL_phoneCall phoneCall = new TL_phone.TL_phoneCall();
        fill(call, phoneCall);
        phoneCall.start_date = now();
        phoneCall.protocol = protocol();
        return phoneCall;
    }

    private TL_phone.PhoneCall discarded(Call call, TLRPC.PhoneCallDiscardReason reason) {
        call.finished = true;
        calls.remove(call.id);
        TL_phone.TL_phoneCallDiscarded phoneCall = new TL_phone.TL_phoneCallDiscarded();
        fill(call, phoneCall);
        phoneCall.reason = reason;
        return phoneCall;
    }

    private TL_phone.PhoneCall requested(Call call) {
        TL_phone.phoneCallRequested phoneCall = new TL_phone.phoneCallRequested();
        fill(call, phoneCall);
        phoneCall.g_a_hash = new byte[32];
        phoneCall.protocol = protocol();
        return phoneCall;
    }

    private TL_phone.PhoneCall waiting(Call call, int receiveDate) {
        TL_phone.TL_phoneCallWaiting phoneCall = new TL_phone.TL_phoneCallWaiting();
        fill(call, phoneCall);
        phoneCall.receive_date = receiveDate;
        phoneCall.protocol = protocol();
        return phoneCall;
    }

    private void fill(Call call, TL_phone.PhoneCall phoneCall) {
        phoneCall.id = call.id;
        phoneCall.access_hash = call.id;
        phoneCall.date = now();
        phoneCall.admin_id = call.outgoing ? selfId : call.peerId;
        phoneCall.participant_id = call.outgoing ? call.peerId : selfId;
        phoneCall.video = call.video;
    }

    private static TL_phone.TL_phoneCallProtocol protocol() {
        TL_phone.TL_phoneCallProtocol protocol = new TL_phone.TL_phoneCallProtocol();
        protocol.udp_p2p = true;
        protocol.library_versions.add("xmpp");
        return protocol;
    }

    private static TL_phone.TL_phone_phoneCall phoneCall(TL_phone.PhoneCall call) {
        TL_phone.TL_phone_phoneCall result = new TL_phone.TL_phone_phoneCall();
        result.phone_call = call;
        return result;
    }
}
