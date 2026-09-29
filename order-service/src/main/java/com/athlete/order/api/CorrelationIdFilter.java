package com.athlete.order.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.athlete.order.infrastructure.observability.CorrelationContext;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes a correlation id for every request: it reuses an inbound {@code X-Correlation-Id}
 * header or generates one, puts it on the SLF4J {@link MDC} so it appears in every structured log
 * line for the request, and echoes it back on the response. This is the lightweight tracing
 * primitive that ties an order's logs together end to end.
 *
 * <p>The id is normalized to a UUID. It is persisted alongside every event and outbox row, where
 * the column is {@code UUID}, so an arbitrary inbound string cannot be carried through; a header
 * that is not a UUID is replaced by a generated one and the replacement is echoed back, so the
 * caller can still see which id its request was recorded under.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = CorrelationContext.MDC_KEY;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = normalize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Returns the header when it is a usable UUID, otherwise a freshly generated one. */
    private static String normalize(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(headerValue.trim()).toString();
        } catch (IllegalArgumentException notAUuid) {
            return UUID.randomUUID().toString();
        }
    }
}
