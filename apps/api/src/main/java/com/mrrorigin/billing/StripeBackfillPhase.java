package com.mrrorigin.billing;

/**
 * Backfill phases, always run in this order for a given connection. Customers and prices are
 * imported before subscriptions/invoices/charges/refunds so downstream reporting (#14+) can rely
 * on the referenced Stripe IDs already having a normalized row in the common case, even though no
 * foreign key enforces it (see V7's migration comment).
 */
enum StripeBackfillPhase {
    CUSTOMERS,
    PRICES,
    SUBSCRIPTIONS,
    INVOICES,
    CHARGES,
    REFUNDS,
    DONE;

    StripeBackfillPhase next() {
        return switch (this) {
            case CUSTOMERS -> PRICES;
            case PRICES -> SUBSCRIPTIONS;
            case SUBSCRIPTIONS -> INVOICES;
            case INVOICES -> CHARGES;
            case CHARGES -> REFUNDS;
            case REFUNDS, DONE -> DONE;
        };
    }
}
