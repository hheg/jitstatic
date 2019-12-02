package io.jitstatic.injection.configuration.reporting;

import java.util.Locale;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.util.Duration;

public class ConsoleReporting {

	@JsonProperty
	private Duration durations = Duration.seconds(5);

	@JsonProperty
	@Pattern(regexp = "[sSmMhH]")
	private String rates = "s";

	@JsonProperty
	private Duration reportPeriods = Duration.minutes(1);

	public Duration getDurations() {
		return durations;
	}

	public void setDurations(Duration durations) {
		this.durations = durations;
	}

	public String getRates() {
		return rates;
	}

	public void setRates(String rates) {
		this.rates = rates;
	}

	public Duration getReportPeriods() {
		return reportPeriods;
	}

	public void setReportPeriods(Duration reportPeriods) {
		this.reportPeriods = reportPeriods;
	}

    public static final Function<String, TimeUnit> convertRate = s -> {
		switch (s.toLowerCase(Locale.ROOT)) {
		case "s":
			return TimeUnit.SECONDS;
		case "m":
			return TimeUnit.MINUTES;
		case "h":
			return TimeUnit.HOURS;
		default:
			throw new IllegalArgumentException(s);
		}
	};
}
