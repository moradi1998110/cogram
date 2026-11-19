package org.telegram.cogram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * The notification settings Telegram keeps on its server: each chat's mute, silence and sound, and the
 * defaults for private chats, groups and channels. XMPP has nothing for them, so they stay on the phone.
 *
 * Without this store Telegram's own settings were lost: every chat and chat details answer carried empty
 * settings, which Telegram applies over what the user chose, so a muted group rang again.
 */
final class NotifySettingsStore {

    private final SharedPreferences preferences;

    NotifySettingsStore(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(
                account == 0 ? "cogram_notify" : "cogram_notify_" + account, Context.MODE_PRIVATE);
    }

    /** The store's key for what a Telegram request names, or null for what it does not keep. */
    static String key(TLRPC.InputNotifyPeer peer) {
        if (peer instanceof TLRPC.TL_inputNotifyUsers) {
            return "users";
        } else if (peer instanceof TLRPC.TL_inputNotifyChats) {
            return "chats";
        } else if (peer instanceof TLRPC.TL_inputNotifyBroadcasts) {
            return "broadcasts";
        } else if (peer instanceof TLRPC.TL_inputNotifyPeer) {
            return key(((TLRPC.TL_inputNotifyPeer) peer).peer);
        } else if (peer instanceof TLRPC.TL_inputNotifyForumTopic) {
            TLRPC.TL_inputNotifyForumTopic topic = (TLRPC.TL_inputNotifyForumTopic) peer;
            String chat = key(topic.peer);
            return chat == null ? null : chat + "_" + topic.top_msg_id;
        }
        return null;
    }

    private static String key(TLRPC.InputPeer peer) {
        if (peer == null) {
            return null;
        } else if (peer.channel_id != 0) {
            return chatKey(peer.channel_id);
        } else if (peer.chat_id != 0) {
            return chatKey(peer.chat_id);
        } else if (peer.user_id != 0) {
            return userKey(peer.user_id);
        }
        return null;
    }

    static String userKey(long userId) {
        return "u" + userId;
    }

    static String chatKey(long chatId) {
        return "c" + chatId;
    }

    synchronized void put(String key, TLRPC.TL_inputPeerNotifySettings input) {
        TLRPC.TL_peerNotifySettings settings = new TLRPC.TL_peerNotifySettings();
        if ((input.flags & TLObject.FLAG_0) != 0) {
            settings.show_previews = input.show_previews;
            settings.flags |= TLObject.FLAG_0;
        }
        if ((input.flags & TLObject.FLAG_1) != 0) {
            settings.silent = input.silent;
            settings.flags |= TLObject.FLAG_1;
        }
        if ((input.flags & TLObject.FLAG_2) != 0) {
            settings.mute_until = input.mute_until;
            settings.flags |= TLObject.FLAG_2;
        }
        if ((input.flags & TLObject.FLAG_3) != 0 && input.sound != null) {
            settings.android_sound = input.sound;
            settings.flags |= TLObject.FLAG_4;
        }
        if ((input.flags & TLObject.FLAG_6) != 0) {
            settings.stories_muted = input.stories_muted;
            settings.flags |= TLObject.FLAG_6;
        }
        if ((input.flags & TLObject.FLAG_7) != 0) {
            settings.stories_hide_sender = input.stories_hide_sender;
            settings.flags |= TLObject.FLAG_7;
        }
        if ((input.flags & TLObject.FLAG_8) != 0 && input.stories_sound != null) {
            settings.stories_android_sound = input.stories_sound;
            settings.flags |= TLObject.FLAG_9;
        }
        SerializedData data = new SerializedData(settings.getObjectSize());
        settings.serializeToStream(data);
        preferences.edit().putString(key, Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)).apply();
        data.cleanup();
    }

    /** The settings kept under a key, or empty settings (Telegram's defaults) when there are none. */
    synchronized TLRPC.PeerNotifySettings get(String key) {
        String stored = key == null ? null : preferences.getString(key, null);
        if (stored != null) {
            try {
                SerializedData data = new SerializedData(Base64.decode(stored, Base64.NO_WRAP));
                TLRPC.PeerNotifySettings settings = TLRPC.PeerNotifySettings.TLdeserialize(data, data.readInt32(false), false);
                data.cleanup();
                if (settings != null) {
                    return settings;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return new TLRPC.TL_peerNotifySettings();
    }

    synchronized void reset() {
        preferences.edit().clear().apply();
    }

    /** Puts the kept settings into an answer that carries chats' settings, as Telegram's server does. */
    void fill(TLObject response) {
        if (response instanceof TLRPC.messages_Dialogs) {
            fillDialogs(((TLRPC.messages_Dialogs) response).dialogs);
        } else if (response instanceof TLRPC.TL_messages_peerDialogs) {
            fillDialogs(((TLRPC.TL_messages_peerDialogs) response).dialogs);
        } else if (response instanceof TLRPC.TL_users_userFull) {
            TLRPC.UserFull full = ((TLRPC.TL_users_userFull) response).full_user;
            if (full != null) {
                full.notify_settings = get(userKey(full.id));
            }
        } else if (response instanceof TLRPC.TL_messages_chatFull) {
            TLRPC.ChatFull full = ((TLRPC.TL_messages_chatFull) response).full_chat;
            if (full != null) {
                full.notify_settings = get(chatKey(full.id));
            }
        }
    }

    private void fillDialogs(java.util.List<TLRPC.Dialog> dialogs) {
        for (TLRPC.Dialog dialog : dialogs) {
            if (dialog.peer == null) {
                continue;
            }
            long chatId = dialog.peer.channel_id != 0 ? dialog.peer.channel_id : dialog.peer.chat_id;
            dialog.notify_settings = get(chatId != 0 ? chatKey(chatId) : userKey(dialog.peer.user_id));
        }
    }
}
