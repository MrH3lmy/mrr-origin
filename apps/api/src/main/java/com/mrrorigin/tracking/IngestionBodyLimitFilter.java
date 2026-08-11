package com.mrrorigin.tracking;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces the public ingestion contract's 1 MiB wire-level request-body limit. */
@Component
final class IngestionBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String INGESTION_PATH = "/api/public/v1/events";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !INGESTION_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writeTooLarge(response);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    static void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"request_too_large\",\"message\":\"Request body exceeds 1048576 bytes\"}");
    }

    static boolean causedByBodyLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestBodyTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream());
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private int bytesRead;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1 && ++bytesRead > MAX_BODY_BYTES) {
                throw new RequestBodyTooLargeException();
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int remaining = MAX_BODY_BYTES - bytesRead + 1;
            int read = delegate.read(bytes, offset, Math.min(length, Math.max(remaining, 1)));
            if (read != -1) {
                bytesRead += read;
                if (bytesRead > MAX_BODY_BYTES) {
                    throw new RequestBodyTooLargeException();
                }
            }
            return read;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }

    private static final class RequestBodyTooLargeException extends IOException {}
}
