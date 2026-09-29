package com.athlete.order.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generates_a_correlation_id_when_absent_echoes_it_and_clears_the_mdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenDuringRequest = new String[1];
        FilterChain chain = (req, res) -> seenDuringRequest[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(seenDuringRequest[0]).isNotBlank();                              // set during the request
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seenDuringRequest[0]); // echoed
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();                  // cleared afterward
    }

    @Test
    void reuses_an_inbound_correlation_id() throws Exception {
        String inbound = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenDuringRequest = new String[1];
        FilterChain chain = (req, res) -> seenDuringRequest[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(seenDuringRequest[0]).isEqualTo(inbound);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(inbound);
    }

    @Test
    void replaces_an_inbound_id_that_is_not_a_uuid() throws Exception {
        // The id is persisted to UUID columns on events and outbox, so a free-form value cannot be
        // carried through. The substitute is echoed back so the caller knows what was recorded.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenDuringRequest = new String[1];
        FilterChain chain = (req, res) -> seenDuringRequest[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(seenDuringRequest[0]).isNotEqualTo("abc-123");
        assertThat(UUID.fromString(seenDuringRequest[0])).isNotNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seenDuringRequest[0]);
    }
}
