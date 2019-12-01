package io.jitstatic.injection.configuration.reporting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.dropwizard.util.Duration;

class ConsoleReportingTest {

    @Test
    void testRates() {
        ConsoleReporting cr = new ConsoleReporting();
        cr.setDurations(Duration.seconds(1));
        cr.setRates("s");
        cr.setReportPeriods(Duration.seconds(5));
        assertEquals(TimeUnit.SECONDS, ConsoleReporting.convertRate.apply(cr.getRates()));
    }

}
