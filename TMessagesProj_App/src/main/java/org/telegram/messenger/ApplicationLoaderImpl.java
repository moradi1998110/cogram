package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;

import java.io.File;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    // Updates from the organization's GitLab (PhogramUpdater), through Telegram's own update sheet and bar; only in
    // builds given an update address.

    private UpdateAppAlertDialog shownDialog;

    @Override
    public boolean isCustomUpdate() {
        return PhogramUpdater.isEnabled();
    }

    @Override
    public String weatherUrl() {
        return BuildConfig.WEATHER_URL;
    }

    @Override
    public String xmppFallbackHost() {
        return BuildConfig.XMPP_FALLBACK_HOST;
    }

    @Override
    public int xmppFallbackPort() {
        return BuildConfig.XMPP_FALLBACK_PORT;
    }

    @Override
    public BetaUpdate getUpdate() {
        return isCustomUpdate() ? PhogramUpdater.getInstance().getUpdate() : null;
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        if (!isCustomUpdate()) return;
        PhogramUpdater.getInstance().checkForUpdate(force, () -> {
            if (whenDone != null) whenDone.run();
            // A required update is offered again each time the app comes to the front.
            if (PhogramUpdater.getInstance().isMandatory() && !isDownloadingUpdate()) {
                PhogramUpdater.getInstance().showPopup();
            }
        });
    }

    @Override
    public void downloadUpdate() {
        if (isCustomUpdate()) PhogramUpdater.getInstance().downloadUpdate();
    }

    @Override
    public void cancelDownloadingUpdate() {
        if (isCustomUpdate()) PhogramUpdater.getInstance().cancelDownloadingUpdate();
    }

    @Override
    public boolean isDownloadingUpdate() {
        return isCustomUpdate() && PhogramUpdater.getInstance().isDownloading();
    }

    @Override
    public float getDownloadingUpdateProgress() {
        return isCustomUpdate() ? PhogramUpdater.getInstance().getDownloadingProgress() : 0;
    }

    @Override
    public File getDownloadedUpdateFile() {
        return isCustomUpdate() ? PhogramUpdater.getInstance().getDownloadedFile() : null;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return isCustomUpdate() ? new UpdateLayout(activity, sideMenuContainer) : null;
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (shownDialog != null && shownDialog.isShowing()) return true;
        android.util.Log.w("PhogramUpdate", "showing the update sheet for " + update.version);
        try {
            shownDialog = new UpdateAppAlertDialog(context, update, account);
            shownDialog.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }
}
