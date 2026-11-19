package org.telegram.cogram.billing;

import java.util.List;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public final class BillingFlowParams {

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder setProductDetailsParamsList(List<ProductDetailsParams> list) {
            return this;
        }

        public Builder setObfuscatedAccountId(String id) {
            return this;
        }

        public Builder setObfuscatedProfileId(String id) {
            return this;
        }

        public Builder setSubscriptionUpdateParams(SubscriptionUpdateParams params) {
            return this;
        }

        public Builder setIsOfferPersonalized(boolean personalized) {
            return this;
        }

        public BillingFlowParams build() {
            return new BillingFlowParams();
        }
    }

    public static final class ProductDetailsParams {
        private final ProductDetails details;

        private ProductDetailsParams(ProductDetails details) {
            this.details = details;
        }

        /** The product's details; Telegram calls Google's obfuscated getter by this name. */
        public ProductDetails zza() {
            return details;
        }

        public static Builder newBuilder() {
            return new Builder();
        }

        public static final class Builder {
            private ProductDetails details;

            public Builder setProductDetails(ProductDetails details) {
                this.details = details;
                return this;
            }

            public Builder setOfferToken(String token) {
                return this;
            }

            public ProductDetailsParams build() {
                return new ProductDetailsParams(details);
            }
        }
    }

    public static final class ProrationMode {
        public static final int IMMEDIATE_WITH_TIME_PRORATION = 1;
        public static final int IMMEDIATE_AND_CHARGE_PRORATED_PRICE = 2;
        public static final int IMMEDIATE_WITHOUT_PRORATION = 3;
        public static final int DEFERRED = 4;
        public static final int IMMEDIATE_AND_CHARGE_FULL_PRICE = 5;

        private ProrationMode() {
        }
    }

    public static final class SubscriptionUpdateParams {
        public static Builder newBuilder() {
            return new Builder();
        }

        public static final class ReplacementMode {
            public static final int WITH_TIME_PRORATION = 1;
            public static final int CHARGE_PRORATED_PRICE = 2;
            public static final int WITHOUT_PRORATION = 3;
            public static final int CHARGE_FULL_PRICE = 5;
            public static final int DEFERRED = 6;

            private ReplacementMode() {
            }
        }

        public static final class Builder {
            public Builder setOldPurchaseToken(String token) {
                return this;
            }

            public Builder setSubscriptionReplacementMode(int mode) {
                return this;
            }

            public Builder setReplaceProrationMode(int mode) {
                return this;
            }

            public SubscriptionUpdateParams build() {
                return new SubscriptionUpdateParams();
            }
        }
    }
}
