package org.telegram.cogram.billing;

import java.util.Collections;
import java.util.List;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). No purchase is ever made. */
public final class Purchase {

    public static final class PurchaseState {
        public static final int UNSPECIFIED_STATE = 0;
        public static final int PURCHASED = 1;
        public static final int PENDING = 2;

        private PurchaseState() {
        }
    }

    private Purchase() {
    }

    public List<String> getProducts() {
        return Collections.emptyList();
    }

    public String getPurchaseToken() {
        return "";
    }

    public String getOrderId() {
        return null;
    }

    public int getPurchaseState() {
        return PurchaseState.UNSPECIFIED_STATE;
    }

    public boolean isAcknowledged() {
        return false;
    }

    public String getOriginalJson() {
        return "{}";
    }

    public String getPackageName() {
        return "";
    }

    public AccountIdentifiers getAccountIdentifiers() {
        return null;
    }
}
