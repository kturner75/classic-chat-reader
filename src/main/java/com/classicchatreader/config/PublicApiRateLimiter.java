package com.classicchatreader.config;

import java.time.Duration;

public interface PublicApiRateLimiter {

    boolean tryConsume(String key, int maxRequests, Duration window);
}
