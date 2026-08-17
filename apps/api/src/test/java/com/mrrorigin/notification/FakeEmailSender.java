package com.mrrorigin.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.mrrorigin.notification.EmailSender.EmailMessage;
import com.mrrorigin.notification.EmailSender.EmailSendException;
import com.mrrorigin.notification.EmailSender.EmailSendResult;

/**
 * Test double for {@link EmailSender} (ADR-0007's test strategy): records every call, and can be
 * configured to fail (transiently or permanently) for the next N calls before succeeding, so retry
 * and terminal-failure behavior can be exercised without any network dependency. {@code @Primary}
 * so it always wins over the real {@code PostmarkEmailSender} bean in tests that boot the full
 * Spring context.
 */
@Component
@Primary
class FakeEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile int failNextCalls = 0;
    private volatile boolean failPermanently = false;

    @Override
    public synchronized EmailSendResult send(EmailMessage message) {
        callCount.incrementAndGet();
        sent.add(message);
        if (failNextCalls > 0) {
            failNextCalls--;
            throw new EmailSendException("simulated failure", failPermanently);
        }
        return new EmailSendResult("fake-message-" + callCount.get());
    }

    void failNextCalls(int count, boolean permanent) {
        this.failNextCalls = count;
        this.failPermanently = permanent;
    }

    int callCount() {
        return callCount.get();
    }

    List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    void reset() {
        sent.clear();
        callCount.set(0);
        failNextCalls = 0;
        failPermanently = false;
    }
}
