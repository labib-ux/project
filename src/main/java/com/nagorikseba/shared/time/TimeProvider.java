package com.nagorikseba.shared.time;

import java.time.Instant;
import java.time.ZoneId;

public interface TimeProvider {
    Instant instant();
    ZoneId getZone();
}