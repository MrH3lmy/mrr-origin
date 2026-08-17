package com.mrrorigin.notification;

/**
 * Provider-neutral email delivery port (#59, ADR-0007). {@link WeeklySummaryDispatchService} depends
 * only on this interface, never on a provider-specific client, so a future provider swap or a test
 * double never touches dispatch/retry call sites.
 */
interface EmailSender {

    EmailSendResult send(EmailMessage message);

    record EmailMessage(
            String toAddress,
            String fromAddress,
            String replyToAddress,
            String subject,
            String textBody,
            String htmlBody) {}

    record EmailSendResult(String providerMessageId) {}

    /**
     * Thrown for any send failure. {@link #permanent()} distinguishes an address-level failure that
     * will never succeed on retry (e.g. invalid/inactive recipient) from a transient one (timeout,
     * 5xx, rate limit) that should follow the normal backoff schedule -- see ADR-0007's "Timeout and
     * error behavior" section for the exact provider-side classification.
     */
    final class EmailSendException extends RuntimeException {

        private final boolean permanent;

        EmailSendException(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }

        EmailSendException(String message, boolean permanent, Throwable cause) {
            super(message, cause);
            this.permanent = permanent;
        }

        boolean permanent() {
            return permanent;
        }
    }
}
