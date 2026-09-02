package com.nagorikseba.shared.time;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

@Component
@Primary
public class SystemClock implements Clock {

    private final java.time.Clock clock = Clock.systemUTC();

    @Override
    public Instant instant() {
        return clock.instant();
    }

    @Override
    public ZoneId getZone() {
        return clock.getZone();
    }
}