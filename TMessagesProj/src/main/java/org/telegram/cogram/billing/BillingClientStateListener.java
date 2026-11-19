package org.telegram.cogram.billing;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public interface BillingClientStateListener {
    void onBillingSetupFinished(BillingResult billingResult);

    void onBillingServiceDisconnected();
}
