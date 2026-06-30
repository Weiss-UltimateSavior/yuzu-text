package com.yuzugame.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminControllerTest {

    private final AdminController controller = new AdminController(null);

    @Test
    void resolveClientIp_ignoresForwardedForFromUntrustedRemote() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");

        String ip = controller.resolveClientIp("1.2.3.4", request);

        assertEquals("8.8.8.8", ip);
    }

    @Test
    void resolveClientIp_usesForwardedForFromTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        String ip = controller.resolveClientIp("1.2.3.4, 10.0.0.2", request);

        assertEquals("1.2.3.4", ip);
    }
}
