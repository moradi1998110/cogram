package org.telegram.cogram.billing;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public final class ConsumeParams {
    final String purchaseToken;

    private ConsumeParams(String purchaseToken) {
        this.purchaseToken = purchaseToken;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private String purchaseToken;

        public Builder setPurchaseToken(String purchaseToken) {
            this.purchaseToken = purchaseToken;
            return this;
        }

        public ConsumeParams build() {
            return new ConsumeParams(purchaseToken);
        }
    }
}
