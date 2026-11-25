package dev.audioscope;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;

/** A seekable, checkpointed WAV owned by the capture process, independent of the UI pipe. */
public final class PcmWaveSink implements Closeable {
  private final FileOutputStream output;
  private final FileChannel channel;
  private final int rate, channels;
  private long bytes, firstNs, lastNs, checkpointNs, pausedAt, pausedNs;
  private boolean paused, closed, audible;

  public PcmWaveSink(FileOutputStream output, int rate, int channels) throws IOException {
    this.output = output;
    this.channel = output.getChannel();
    this.rate = rate;
    this.channels = channels;
    channel.truncate(0);
    header();
  }

  public synchronized void pause(boolean value, long now) {
    if (value == paused) return;
    if (value) pausedAt = now;
    else pausedNs += Math.max(0, now - pausedAt);
    paused = value;
  }

  public synchronized void write(byte[] data, int count, long timeNs) throws IOException {
    if (closed || paused) return;
    if (count < 0 || count > data.length || count % (channels * 2) != 0)
      throw new IOException("Invalid PCM frame count");
    if (bytes + count > 0xfffffff0L - 44) throw new IOException("WAV size limit reached");
    if (firstNs == 0) firstNs = timeNs - pausedNs;
    lastNs = timeNs - pausedNs + (long) count * 1000000000L / (rate * channels * 2L);
    ByteBuffer buffer = ByteBuffer.wrap(data, 0, count);
    while (buffer.hasRemaining()) channel.write(buffer);
    bytes += count;
    if (!audible) audible = new PcmStats(data, count).db > -60;
    long now = System.nanoTime();
    if (now - checkpointNs >= 5_000_000_000L) {
      header();
      output.getFD().sync();
      checkpointNs = now;
    }
  }

  private void header() throws IOException {
    ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x46464952)
        .putInt((int) (36 + bytes))
        .putInt(0x45564157)
        .putInt(0x20746d66)
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) channels)
        .putInt(rate)
        .putInt(rate * channels * 2)
        .putShort((short) (channels * 2))
        .putShort((short) 16)
        .putInt(0x61746164)
        .putInt((int) bytes)
        .flip();
    long position = channel.position();
    channel.position(0);
    while (b.hasRemaining()) channel.write(b);
    channel.position(Math.max(44, position));
  }

  public synchronized long frames() {
    return bytes / (channels * 2);
  }

  public synchronized long firstNs() {
    return firstNs;
  }

  public synchronized long lastNs() {
    return lastNs;
  }

  public synchronized boolean audible() {
    return audible;
  }

  public synchronized void close() throws IOException {
    if (closed) return;
    try {
      header();
      output.getFD().sync();
    } finally {
      closed = true;
      output.close();
    }
  }
}
