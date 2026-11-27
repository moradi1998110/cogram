package org.telegram.cogram;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Settings > Notifications > Background activity: how the phone treats Phogram's connection, each row opening
 * the system screen that changes it. The rows are read again whenever the user comes back from one.
 */
public class BackgroundActivity extends UniversalFragment {

    private static final int ID_NOTIFICATIONS = 1;
    private static final int ID_CALL_SCREEN = 2;
    private static final int ID_BATTERY = 3;
    private static final int ID_USAGE = 4;
    private static final int ID_VENDOR = 5;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PhogramBackground);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        if (context == null) return;

        items.add(UItem.asButton(ID_NOTIFICATIONS, R.drawable.msg2_notifications,
                LocaleController.getString(R.string.PhogramBackgroundNotifications),
                allowed(BackgroundHealth.notificationsAllowed(context))));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            items.add(UItem.asButton(ID_CALL_SCREEN, R.drawable.msg_calls,
                    LocaleController.getString(R.string.PhogramBackgroundCallScreen),
                    allowed(BackgroundHealth.callScreenAllowed(context))));
        }
        items.add(UItem.asButton(ID_BATTERY, R.drawable.msg2_battery,
                LocaleController.getString(R.string.PhogramBackgroundBattery),
                unrestricted(BackgroundHealth.batteryUnrestricted(context))));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            items.add(UItem.asButton(ID_USAGE, R.drawable.msg_background,
                    LocaleController.getString(R.string.PhogramBackgroundUsage),
                    unrestricted(!BackgroundHealth.backgroundRestricted(context))));
        }
        final int vendorTitle = vendorTitle();
        if (vendorTitle != 0) {
            items.add(UItem.asButton(ID_VENDOR, R.drawable.msg_settings, LocaleController.getString(vendorTitle)));
        }

        final StringBuilder hint = new StringBuilder();
        final int vendorHint = vendorHint();
        if (vendorHint != 0) {
            hint.append(LocaleController.getString(vendorHint)).append("\n\n");
        }
        hint.append(LocaleController.getString(R.string.PhogramBackgroundHint));
        final long lastStop = BackgroundHealth.lastStop(context);
        if (lastStop > 0) {
            hint.append("\n\n").append(LocaleController.formatString(R.string.PhogramBackgroundLastStop,
                    LocaleController.formatDateAudio(lastStop / 1000, false)));
        }
        items.add(UItem.asShadow(hint));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final Activity activity = getParentActivity();
        if (activity == null) return;
        switch (item.id) {
            case ID_NOTIFICATIONS:
                BackgroundHealth.openNotificationSettings(activity);
                break;
            case ID_CALL_SCREEN:
                BackgroundHealth.openCallScreenSettings(activity);
                break;
            case ID_BATTERY:
                BackgroundHealth.openBatterySettings(activity);
                break;
            case ID_USAGE:
                BackgroundHealth.openAppDetails(activity);
                break;
            case ID_VENDOR:
                BackgroundHealth.openVendorSettings(activity);
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) listView.adapter.update(true);
    }

    private static String allowed(boolean allowed) {
        return LocaleController.getString(allowed ? R.string.PhogramBackgroundAllowed : R.string.PhogramBackgroundOff);
    }

    private static String unrestricted(boolean unrestricted) {
        return LocaleController.getString(unrestricted ? R.string.PhogramBackgroundUnrestricted : R.string.PhogramBackgroundRestricted);
    }

    private static int vendorTitle() {
        switch (BackgroundHealth.vendor()) {
            case BackgroundHealth.VENDOR_HUAWEI: return R.string.PhogramBackgroundVendorHuawei;
            case BackgroundHealth.VENDOR_XIAOMI: return R.string.PhogramBackgroundVendorXiaomi;
            case BackgroundHealth.VENDOR_OPPO: return R.string.PhogramBackgroundVendorOppo;
            case BackgroundHealth.VENDOR_VIVO: return R.string.PhogramBackgroundVendorVivo;
            case BackgroundHealth.VENDOR_SAMSUNG: return R.string.PhogramBackgroundVendorSamsung;
            default: return 0;
        }
    }

    private static int vendorHint() {
        switch (BackgroundHealth.vendor()) {
            case BackgroundHealth.VENDOR_HUAWEI: return R.string.PhogramBackgroundVendorHuaweiHint;
            case BackgroundHealth.VENDOR_XIAOMI: return R.string.PhogramBackgroundVendorXiaomiHint;
            case BackgroundHealth.VENDOR_OPPO: return R.string.PhogramBackgroundVendorOppoHint;
            case BackgroundHealth.VENDOR_VIVO: return R.string.PhogramBackgroundVendorVivoHint;
            case BackgroundHealth.VENDOR_SAMSUNG: return R.string.PhogramBackgroundVendorSamsungHint;
            default: return 0;
        }
    }
}
