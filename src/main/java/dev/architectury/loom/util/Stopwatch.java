package dev.architectury.loom.util;

import java.time.Duration;
import java.time.Instant;

import org.jetbrains.annotations.Nullable;

public final class Stopwatch {
	private @Nullable Instant start;

	private Stopwatch() {
	}

	public boolean isRunning() {
		return start != null;
	}

	public void start() {
		if (isRunning()) {
			throw new IllegalStateException("Stopwatch already started");
		}

		start = Instant.now();
	}

	public Stopped stop() {
		if (!isRunning()) {
			throw new IllegalStateException("Stopwatch not started");
		}

		final Instant end = Instant.now();
		final Duration duration = Duration.between(start, end);
		return new Stopped(duration);
	}

	public static Stopwatch createStarted() {
		var stopwatch = new Stopwatch();
		stopwatch.start();
		return stopwatch;
	}

	public static Stopwatch createUnstarted() {
		return new Stopwatch();
	}

	@Override
	public String toString() {
		return isRunning() ? "Stopwatch[running]" : "Stopwatch[unstarted]";
	}

	public record Stopped(Duration duration) {
		@Override
		public String toString() {
			if (duration.toDays() > 0) {
				return duration.toDays() + "d";
			} else if (duration.toHours() > 0) {
				return duration.toHours() + "h " + duration.toMinutesPart() + "min";
			} else if (duration.toMinutes() > 0) {
				return duration.toMinutes() + "min " + duration.toSecondsPart() + "s";
			} else if (duration.toSeconds() > 0) {
				return duration.toSeconds() + "s";
			} else if (duration.toMillis() > 0) {
				return duration.toMillis() + "ms";
			}

			return duration.toNanos() + "ns";
		}
	}
}
