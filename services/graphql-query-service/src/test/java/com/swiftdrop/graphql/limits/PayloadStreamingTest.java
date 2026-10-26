package com.swiftdrop.graphql.limits;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class PayloadStreamingTest {
    @Test void unknownLengthBodyStopsReadingAtLimitAndNeverReachesGraphql() throws Exception {
        var bytesRead = new AtomicInteger();
        var request = new MockHttpServletRequest("POST", "/graphql") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { bytesRead.incrementAndGet(); return 'x'; }
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        var response = new MockHttpServletResponse();
        new BoundedPayloadFilter().doFilter(request, response, (req, res) -> fail("Oversized body reached GraphQL"));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(bytesRead).hasValue(65537);
    }
}
