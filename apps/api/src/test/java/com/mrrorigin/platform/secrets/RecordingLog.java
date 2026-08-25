package com.mrrorigin.platform.secrets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;

/**
 * Minimal {@link Log} test double that records every logged message so tests can assert a resolved
 * secret value never appears in a log line (see {@link SecretResolverTests#neverLogsAResolvedSecretValue}).
 */
final class RecordingLog implements Log {

    private final List<String> messages = new ArrayList<>();

    List<String> messages() {
        return Collections.unmodifiableList(messages);
    }

    private void record(Object message) {
        if (message != null) {
            messages.add(String.valueOf(message));
        }
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public boolean isFatalEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public void trace(Object message) {
        record(message);
    }

    @Override
    public void trace(Object message, Throwable t) {
        record(message);
    }

    @Override
    public void debug(Object message) {
        record(message);
    }

    @Override
    public void debug(Object message, Throwable t) {
        record(message);
    }

    @Override
    public void info(Object message) {
        record(message);
    }

    @Override
    public void info(Object message, Throwable t) {
        record(message);
    }

    @Override
    public void warn(Object message) {
        record(message);
    }

    @Override
    public void warn(Object message, Throwable t) {
        record(message);
    }

    @Override
    public void error(Object message) {
        record(message);
    }

    @Override
    public void error(Object message, Throwable t) {
        record(message);
    }

    @Override
    public void fatal(Object message) {
        record(message);
    }

    @Override
    public void fatal(Object message, Throwable t) {
        record(message);
    }
}
