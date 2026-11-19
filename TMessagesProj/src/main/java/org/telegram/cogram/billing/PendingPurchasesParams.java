package org.telegram.cogram.billing;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public final class PendingPurchasesParams {
    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder enableOneTimeProducts() {
            return this;
        }

        public Builder enablePrepaidPlans() {
            return this;
        }

        public PendingPurchasesParams build() {
            return new PendingPurchasesParams();
        }
    }
}
