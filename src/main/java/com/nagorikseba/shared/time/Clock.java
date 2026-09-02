package com.nagorikseba.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

public interface Clock {
    Instant instant();
    ZoneId getZone();
}