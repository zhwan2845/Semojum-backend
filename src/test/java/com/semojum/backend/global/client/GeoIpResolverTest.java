package com.semojum.backend.global.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeoIpResolverTest {

    @Test
    void 사설_루프백_IP는_조회_대상이_아니다() {
        assertTrue(GeoIpResolver.isPrivate("10.0.0.1"));
        assertTrue(GeoIpResolver.isPrivate("192.168.1.10"));
        assertTrue(GeoIpResolver.isPrivate("172.20.1.10"));
        assertTrue(GeoIpResolver.isPrivate("127.0.0.1"));
        assertFalse(GeoIpResolver.isPrivate("198.51.100.22"));
        assertFalse(GeoIpResolver.isPrivate("172.15.0.1"));   // 172.16~31만 사설
    }
}
