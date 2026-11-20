package com.demo.upimesh;

import com.demo.upimesh.config.ApiRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRateLimitFilterTest {

    @Test
    void rejectsWritesAfterTheConfiguredPerClientLimit() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(2, true);

        MockHttpServletResponse first = performWrite(filter);
        MockHttpServletResponse second = performWrite(filter);
        MockHttpServletResponse third = performWrite(filter);

        assertEquals(200, first.getStatus());
        assertEquals(200, second.getStatus());
        assertEquals(429, third.getStatus());
        assertEquals("60", third.getHeader("Retry-After"));
        assertTrue(third.getContentAsString().contains("rate_limit_exceeded"));
    }

    @Test
    void doesNotRateLimitReadRequests() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(1, true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsWritesWhenPublicDemoWritesAreDisabled() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(60, false);
        MockHttpServletResponse response = performWrite(filter);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("demo_writes_disabled"));
    }

    private MockHttpServletResponse performWrite(ApiRateLimitFilter filter) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/demo/send");
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
