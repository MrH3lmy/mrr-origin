package com.mrrorigin.notification;

/**
 * Provider-neutral email delivery port (#59, ADR-0007). {@link WeeklySummaryDispatchService} depends
 * only on this interface, never on a provider-specific client, so a future provider swap or a test
 * double never touches dispatch/retry call sites.
 */
interface EmailSender {

    EmailSendResult send(EmailMessage message);

    /**
     * {@code deliveryId} is {@code weekly_summary_deliveries.id} -- carried in the provider request
     * (Postmark {@code Metadata} + a custom tracing header) so a rare provider-side duplicate (see
     * {@link EmailSendException#ambiguous()}) is traceable back to the exact delivery attempt on both
     * sides. See the delivery plan's "Delivery guarantee" section.
     */
    record EmailMessage(
            String toAddress,
            String fromAddress,
            String replyToAddress,
            String subject,
            String textBody,
            String htmlBody,
            String deliveryId) {}

    record EmailSendResult(String providerMessageId) {}

    /**
     * Thrown for any send failure. {@link #permanent()} distinguishes an address-level failure that
     * will never succeed on retry (e.g. invalid/inactive recipient) from a transient one (timeout,
     * 5xx, rate limit) that should follow the normal backoff schedule -- see ADR-0007's "Timeout and
     * error behavior" section for the exact provider-side classification.
     *
     * <p>{@link #ambiguous()} is independent of {@link #permanent()}: it is true when a network-level
     * failure means we cannot tell whether Postmark ever received/queued the message (so a retry may
     * rarely produce a provider-side duplicate), and false when Postmark gave a definite HTTP answer
     * (success or a clear error). This is what makes the resulting at-least-once delivery guarantee
     * honest rather than silently folded into an ordinary transient failure -- see the delivery plan's
     * "Delivery guarantee" section.
     */
    final class EmailSendException extends RuntimeException {

        private final boolean permanent;
        private final boolean ambiguous;

        EmailSendException(String message, boolean permanent) {
            this(message, permanent, false, null);
        }

        EmailSendException(String message, boolean permanent, boolean ambiguous) {
            this(message, permanent, ambiguous, null);
        }

        EmailSendException(String message, boolean permanent, Throwable cause) {
            this(message, permanent, true, cause);
        }

        EmailSendException(String message, boolean permanent, boolean ambiguous, Throwable cause) {
            super(message, cause);
            this.permanent = permanent;
            this.ambiguous = ambiguous;
        }

        boolean permanent() {
            return permanent;
        }

        boolean ambiguous() {
            return ambiguous;
        }
    }
}
