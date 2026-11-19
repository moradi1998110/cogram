package org.telegram.cogram.billing;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public interface ProductDetailsResponseListener {
    void onProductDetailsResponse(BillingResult billingResult, QueryProductDetailsResult result);
}
