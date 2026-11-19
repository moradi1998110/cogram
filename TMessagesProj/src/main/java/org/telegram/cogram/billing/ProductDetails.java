package org.telegram.cogram.billing;

import java.util.Collections;
import java.util.List;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). No product is ever offered. */
public final class ProductDetails {

    private ProductDetails() {
    }

    public String getProductId() {
        return "";
    }

    public String getProductType() {
        return "";
    }

    public String getName() {
        return "";
    }

    public String getTitle() {
        return "";
    }

    public String getDescription() {
        return "";
    }

    public OneTimePurchaseOfferDetails getOneTimePurchaseOfferDetails() {
        return null;
    }

    public List<SubscriptionOfferDetails> getSubscriptionOfferDetails() {
        return Collections.emptyList();
    }

    public static final class OneTimePurchaseOfferDetails {
        private OneTimePurchaseOfferDetails() {
        }

        public long getPriceAmountMicros() {
            return 0;
        }

        public String getPriceCurrencyCode() {
            return "";
        }

        public String getFormattedPrice() {
            return "";
        }
    }

    public static final class SubscriptionOfferDetails {
        private SubscriptionOfferDetails() {
        }

        public String getOfferToken() {
            return "";
        }

        public String getBasePlanId() {
            return "";
        }

        public PricingPhases getPricingPhases() {
            return new PricingPhases();
        }
    }

    public static final class PricingPhases {
        PricingPhases() {
        }

        public List<PricingPhase> getPricingPhaseList() {
            return Collections.emptyList();
        }
    }

    public static final class PricingPhase {
        private PricingPhase() {
        }

        public long getPriceAmountMicros() {
            return 0;
        }

        public String getPriceCurrencyCode() {
            return "";
        }

        public String getFormattedPrice() {
            return "";
        }

        public String getBillingPeriod() {
            return "";
        }
    }
}
