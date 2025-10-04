package dev.architectury.loom.util;

import java.io.OutputStream;

public final class NullOutputStream extends OutputStream {
	public static final NullOutputStream INSTANCE = new NullOutputStream();

	private NullOutputStream() {
	}

	@Override
	public void write(int b) {
		// no-op
	}

	@Override
	public void write(byte[] b) {
		// no-op
	}

	@Override
	public void write(byte[] b, int off, int len) {
		// no-op
	}

	@Override
	public void close() {
		// no-op
	}
}
