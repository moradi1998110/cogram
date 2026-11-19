package org.telegram.cogram.billing;

import android.app.Activity;
import android.content.Context;

import java.util.Collections;

/**
 * Phogram: a stand-in for Google Play Billing, which Phogram does not ship. It keeps Telegram's purchase code
 * compiling with nothing behind it: it never connects, offers no products and answers every request with
 * "billing unavailable", so no request reaches Google Play.
 */
public final class BillingClient {

    public static final class BillingResponseCode {
        public static final int SERVICE_TIMEOUT = -3;
        public static final int FEATURE_NOT_SUPPORTED = -2;
        public static final int SERVICE_DISCONNECTED = -1;
        public static final int OK = 0;
        public static final int USER_CANCELED = 1;
        public static final int SERVICE_UNAVAILABLE = 2;
        public static final int BILLING_UNAVAILABLE = 3;
        public static final int ITEM_UNAVAILABLE = 4;
        public static final int DEVELOPER_ERROR = 5;
        public static final int ERROR = 6;
        public static final int ITEM_ALREADY_OWNED = 7;
        public static final int ITEM_NOT_OWNED = 8;
        public static final int NETWORK_ERROR = 12;

        private BillingResponseCode() {
        }
    }

    public static final class ProductType {
        public static final String INAPP = "inapp";
        public static final String SUBS = "subs";

        private ProductType() {
        }
    }

    public static final class Builder {
        Builder() {
        }

        public Builder setListener(PurchasesUpdatedListener listener) {
            return this;
        }

        public Builder enablePendingPurchases(PendingPurchasesParams params) {
            return this;
        }

        public BillingClient build() {
            return new BillingClient();
        }
    }

    private BillingClient() {
    }

    public static Builder newBuilder(Context context) {
        return new Builder();
    }

    static BillingResult unavailable() {
        return BillingResult.newBuilder().setResponseCode(BillingResponseCode.BILLING_UNAVAILABLE).build();
    }

    public boolean isReady() {
        return false;
    }

    /** Never connects; Telegram treats a client that never finishes setting up as not ready. */
    public void startConnection(BillingClientStateListener listener) {
    }

    public void endConnection() {
    }

    public void queryProductDetailsAsync(QueryProductDetailsParams params, ProductDetailsResponseListener listener) {
        listener.onProductDetailsResponse(unavailable(), new QueryProductDetailsResult());
    }

    public void queryPurchasesAsync(QueryPurchasesParams params, PurchasesResponseListener listener) {
        listener.onQueryPurchasesResponse(unavailable(), Collections.emptyList());
    }

    public void consumeAsync(ConsumeParams params, ConsumeResponseListener listener) {
        listener.onConsumeResponse(unavailable(), params.purchaseToken);
    }

    public BillingResult launchBillingFlow(Activity activity, BillingFlowParams params) {
        return unavailable();
    }
}
