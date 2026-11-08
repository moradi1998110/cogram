package org.telegram.cogram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.NativeInstance;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.List;

import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;

/**
 * Stands in for Telegram's native call engine during an XMPP call. Telegram's call service and
 * screen talk to it exactly as to their own engine, and it drives the engine's Jingle connection.
 */
final class XmppCallInstance extends NativeInstance {

    private static final int SIGNAL_BARS_CONNECTED = 4;
    private static final int AUTH_KEY_LENGTH = 256;

    private final JingleRtpConnection connection;
    private final VideoSink remoteSink;
    private VideoSink localSink;
    private VideoTrack attachedLocalTrack;
    private VideoTrack attachedRemoteTrack;
    private boolean established;
    private volatile boolean microphoneMuted;
    private volatile Boolean videoWanted;

    XmppCallInstance(JingleRtpConnection connection, VideoSink remoteSink) {
        this.connection = connection;
        this.remoteSink = remoteSink;
    }

    /** A key both users derive the same way from the call's DTLS fingerprints, for Telegram's emoji. */
    byte[] encryptionKey() {
        List<String> fingerprints = connection.getDtlsFingerprints();
        byte[] text = String.join("\n", fingerprints).getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[AUTH_KEY_LENGTH];
        for (int block = 0; block * 32 < AUTH_KEY_LENGTH; block++) {
            byte[] input = new byte[text.length + 1];
            System.arraycopy(text, 0, input, 0, text.length);
            input[text.length] = (byte) block;
            byte[] hash = Utilities.computeSHA256(input, 0, input.length);
            System.arraycopy(hash, 0, key, block * 32, 32);
        }
        return key;
    }

    void onConnected() {
        AndroidUtilities.runOnUIThread(() -> {
            established = true;
            applyMedia();
            attachVideo();
            onSignalBarsUpdated(SIGNAL_BARS_CONNECTED);
            onStateUpdated(Instance.STATE_ESTABLISHED);
        });
    }

    void onReconnecting() {
        AndroidUtilities.runOnUIThread(() -> onStateUpdated(Instance.STATE_RECONNECTING));
    }

    /** Tracks appear once the peer connection exists, so sinks attach whenever they become available. */
    private void attachVideo() {
        VideoTrack remote = connection.getRemoteVideoTrack().orNull();
        if (remote != null && remote != attachedRemoteTrack) {
            remote.addSink(remoteSink);
            attachedRemoteTrack = remote;
        }
        VideoTrack local = connection.getLocalVideoTrack().orNull();
        if (localSink != null && local != null && local != attachedLocalTrack) {
            local.addSink(localSink);
            attachedLocalTrack = local;
        }
        boolean remoteVideo = remote != null && connection.getMedia().contains(Media.VIDEO);
        onRemoteMediaStateUpdated(Instance.AUDIO_STATE_ACTIVE, remoteVideo ? Instance.VIDEO_STATE_ACTIVE : Instance.VIDEO_STATE_INACTIVE);
    }

    private void detachVideo() {
        try {
            if (attachedRemoteTrack != null) {
                attachedRemoteTrack.removeSink(remoteSink);
            }
            if (attachedLocalTrack != null && localSink != null) {
                attachedLocalTrack.removeSink(localSink);
            }
        } catch (RuntimeException e) {
            // The engine may already have disposed of the tracks when the call ended.
            FileLog.e(e);
        }
        attachedRemoteTrack = null;
        attachedLocalTrack = null;
    }

    @Override
    public Instance.FinalState stop() {
        detachVideo();
        return new Instance.FinalState(null, "", new Instance.TrafficStats(0, 0, 0, 0), false);
    }

    /**
     * Telegram sets the microphone and camera as soon as the call starts, before the engine has
     * created the tracks, so the wanted state is kept and applied again once the call connects.
     */
    private void applyMedia() {
        try {
            connection.setMicrophoneEnabled(!microphoneMuted);
        } catch (IllegalStateException e) {
            // No audio track yet; applied again on connect.
        }
        if (videoWanted != null) {
            try {
                connection.setVideoEnabled(videoWanted);
            } catch (IllegalStateException e) {
                // No video track yet; applied again on connect.
            }
        }
    }

    @Override
    public void setMuteMicrophone(boolean muteMicrophone) {
        microphoneMuted = muteMicrophone;
        applyMedia();
    }

    @Override
    public void setupOutgoingVideo(VideoSink localSink, int type) {
        this.localSink = localSink;
        videoWanted = true;
        applyMedia();
        if (established) {
            attachVideo();
        }
    }

    @Override
    public void setupOutgoingVideoCreated(long videoCapturer) {
    }

    @Override
    public void setVideoState(int videoState) {
        videoWanted = videoState == Instance.VIDEO_STATE_ACTIVE;
        applyMedia();
    }

    @Override
    public void switchCamera(boolean front) {
        try {
            if (connection.isCameraSwitchable() && connection.isFrontCamera() != front) {
                connection.switchCamera();
            }
        } catch (IllegalStateException e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean hasVideoCapturer() {
        return connection.getLocalVideoTrack().isPresent();
    }

    @Override
    public void setNetworkType(int networkType) {
    }

    @Override
    public void setAudioOutputGainControlEnabled(boolean enabled) {
    }

    @Override
    public void setEchoCancellationStrength(int strength) {
    }

    @Override
    public void setNoiseSuppressionEnabled(boolean value) {
    }

    @Override
    public void setVolume(int ssrc, double volume) {
    }

    @Override
    public void setBufferSize(int size) {
    }

    @Override
    public void setGlobalServerConfig(String serverConfigJson) {
    }

    @Override
    public void activateVideoCapturer(long videoCapturer) {
    }

    @Override
    public void clearVideoCapturer() {
    }

    @Override
    public void onSignalingDataReceive(byte[] data) {
    }

    @Override
    public String getLastError() {
        return "";
    }

    @Override
    public String getDebugInfo() {
        return "";
    }

    @Override
    public long getPreferredRelayId() {
        return 0;
    }

    @Override
    public Instance.TrafficStats getTrafficStats() {
        return new Instance.TrafficStats(0, 0, 0, 0);
    }

    @Override
    public byte[] getPersistentState() {
        return null;
    }
}
