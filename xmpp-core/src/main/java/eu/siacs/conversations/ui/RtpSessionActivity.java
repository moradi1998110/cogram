package eu.siacs.conversations.ui;

import android.app.Activity;

/** Stands in for the Conversations screen of this name, which the Telegram UI replaces. */
public class RtpSessionActivity extends Activity {
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_WITH = "with";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_PROPOSED_SESSION_ID = "proposed_session_id";
    public static final String EXTRA_LAST_REPORTED_STATE = "last_reported_state";
    public static final String EXTRA_LAST_ACTION = "last_action";
    public static final String ACTION_ACCEPT_CALL = "action_accept_call";
    public static final String ACTION_MAKE_VOICE_CALL = "action_make_voice_call";
    public static final String ACTION_MAKE_VIDEO_CALL = "action_make_video_call";
}
