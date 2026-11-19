package net.osmand.plus.inapp;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.inapp.InAppPurchases.InAppPurchase.PurchaseOrigin;

import java.lang.ref.WeakReference;

/**
 * Phogram: OsmAnd's purchases without Google Play Billing, which Phogram does not ship. fetch-osmand.sh puts this
 * in place of OsmAnd's Google Play build of the class. Nothing is sold, nothing is bought, and no request goes
 * to Google Play or to OsmAnd's purchase servers.
 */
public class InAppPurchaseHelperImpl extends InAppPurchaseHelper {

	public InAppPurchaseHelperImpl(OsmandApplication ctx) {
		super(ctx);
		purchases = new InAppPurchasesImpl(ctx);
	}

	@Override
	public boolean isPurchasedLocalFullVersion() {
		return false;
	}

	@Override
	public boolean isPurchasedLocalDeepContours() {
		return false;
	}

	@Override
	public boolean isSubscribedToLocalLiveUpdates() {
		return false;
	}

	@Override
	public boolean isSubscribedToLocalOsmAndPro() {
		return false;
	}

	@Override
	public boolean isSubscribedToLocalMaps() {
		return false;
	}

	@Override
	public String getPlatform() {
		return "phogram";
	}

	@Override
	public void isInAppPurchaseSupported(@NonNull Activity activity, @Nullable InAppPurchaseInitCallback callback) {
		if (callback != null) {
			callback.onFail();
		}
	}

	@Override
	protected void execImpl(@NonNull InAppPurchaseTaskType taskType, @NonNull InAppCommand command) {
	}

	@Override
	public void purchaseFullVersion(@NonNull Activity activity) throws UnsupportedOperationException {
		throw new UnsupportedOperationException("Phogram sells nothing");
	}

	@Override
	public void purchaseDepthContours(@NonNull Activity activity) throws UnsupportedOperationException {
		throw new UnsupportedOperationException("Phogram sells nothing");
	}

	@Override
	public void purchaseContourLines(@NonNull Activity activity) throws UnsupportedOperationException {
		throw new UnsupportedOperationException("Phogram sells nothing");
	}

	@Override
	public void manageSubscription(@NonNull Context ctx, @Nullable String sku, @Nullable PurchaseOrigin origin) {
	}

	/** OsmAnd asks its purchase servers for the user's purchases here; there are none, so nobody is asked. */
	@Override
	public void requestInventory(boolean userRequested) {
	}

	@Override
	public void purchaseSubscription(@NonNull Activity activity, String sku) {
	}

	@Override
	protected InAppCommand getPurchaseSubscriptionCommand(WeakReference<Activity> activity, String sku, String userInfo) {
		return new NoCommand();
	}

	@Override
	protected InAppCommand getRequestInventoryCommand(boolean userRequested) {
		return new NoCommand();
	}

	private static final class NoCommand extends InAppCommand {
		@Override
		void run(@NonNull InAppPurchaseHelper helper) {
			commandDone();
		}
	}

	@Override
	protected boolean isBillingManagerExists() {
		return false;
	}

	@Override
	protected boolean isLocalBillingUnavailable() {
		return true;
	}

	@Override
	protected void destroyBillingManager() {
	}
}
