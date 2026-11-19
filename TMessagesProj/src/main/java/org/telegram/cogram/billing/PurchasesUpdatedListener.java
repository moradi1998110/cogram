package org.telegram.cogram.billing;

import java.util.List;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public interface PurchasesUpdatedListener {
    void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases);
}
