package org.telegram.cogram.billing;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public final class BillingResult {
    private final int responseCode;
    private final String debugMessage;

    private BillingResult(int responseCode, String debugMessage) {
        this.responseCode = responseCode;
        this.debugMessage = debugMessage;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public String getDebugMessage() {
        return debugMessage;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private int responseCode;
        private String debugMessage = "";

        public Builder setResponseCode(int responseCode) {
            this.responseCode = responseCode;
            return this;
        }

        public Builder setDebugMessage(String debugMessage) {
            this.debugMessage = debugMessage;
            return this;
        }

        public BillingResult build() {
            return new BillingResult(responseCode, debugMessage);
        }
    }
}
