package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StripeBillingObjectsParsedPriceTests {

    @Test
    void legacyRecurringPriceWithUnknownUsageTypeFailsClosedAsMetered() {
        var price = new StripeBillingObjects.ParsedPrice(
                "price_legacy",
                "prod_legacy",
                "usd",
                1200L,
                "per_unit",
                "recurring",
                "month",
                1,
                null,
                true);

        assertThat(price.usageType()).isEqualTo("metered");
    }

    @Test
    void explicitLicensedUsageTypeIsPreserved() {
        var price = new StripeBillingObjects.ParsedPrice(
                "price_licensed",
                "prod_licensed",
                "usd",
                1200L,
                "per_unit",
                "recurring",
                "month",
                1,
                "licensed",
                true);

        assertThat(price.usageType()).isEqualTo("licensed");
    }

    @Test
    void nonRecurringPriceKeepsNullUsageType() {
        var price = new StripeBillingObjects.ParsedPrice(
                "price_one_time",
                "prod_one_time",
                "usd",
                1200L,
                "per_unit",
                "one_time",
                null,
                null,
                null,
                true);

        assertThat(price.usageType()).isNull();
    }
}
