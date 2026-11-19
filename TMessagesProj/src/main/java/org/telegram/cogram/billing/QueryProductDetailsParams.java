package org.telegram.cogram.billing;

import java.util.List;

/** Phogram: part of the stand-in for Google Play Billing (see BillingClient). */
public final class QueryProductDetailsParams {
    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder setProductList(List<Product> products) {
            return this;
        }

        public QueryProductDetailsParams build() {
            return new QueryProductDetailsParams();
        }
    }

    public static final class Product {
        private Product() {
        }

        public static Builder newBuilder() {
            return new Builder();
        }

        public static final class Builder {
            public Builder setProductId(String productId) {
                return this;
            }

            public Builder setProductType(String productType) {
                return this;
            }

            public Product build() {
                return new Product();
            }
        }
    }
}
