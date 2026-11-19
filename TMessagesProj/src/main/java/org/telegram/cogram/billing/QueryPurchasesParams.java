package org.telegram.cogram.billing;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public final class QueryPurchasesParams {
    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder setProductType(String productType) {
            return this;
        }

        public QueryPurchasesParams build() {
            return new QueryPurchasesParams();
        }
    }
}
