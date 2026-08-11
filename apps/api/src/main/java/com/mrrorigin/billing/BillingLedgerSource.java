package com.mrrorigin.billing;

/** Which pipeline produced a given normalized ledger row's current state; purely informational. */
enum BillingLedgerSource {
    BACKFILL,
    WEBHOOK
}
