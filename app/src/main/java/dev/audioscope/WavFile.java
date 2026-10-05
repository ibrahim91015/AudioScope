package dev.audioscope;

import java.io.*;
import java.nio.*;

/** Append-only PCM16 WAV with crash-repairable length fields. */
public final class WavFile implements Closeable {
  private final RandomAccessFile file;
  private final int rate, channels;
  private long bytes;

  public WavFile(File path, int rate, int channels) throws IOException {
    this.rate = rate;
    this.channels = channels;
    file = new RandomAccessFile(path, "rw");
    file.setLength(0);
    header();
  }

  private void header() throws IOException {
    ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes("US-ASCII"))
        .putInt((int) (36 + bytes))
        .put("WAVEfmt ".getBytes("US-ASCII"))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) channels)
        .putInt(rate)
        .putInt(rate * channels * 2)
        .putShort((short) (channels * 2))
        .putShort((short) 16)
        .put("data".getBytes("US-ASCII"))
        .putInt((int) bytes);
    long pos = file.getFilePointer();
    file.seek(0);
    file.write(b.array());
    file.seek(Math.max(44, pos));
  }

  public synchronized void write(byte[] pcm, int n) throws IOException {
    if (bytes + n > 0xfffffff0L - 44)
      throw new IOException("WAV size limit reached; start a new session");
    file.write(pcm, 0, n);
    bytes += n;
  }

  public synchronized void checkpoint() throws IOException {
    header();
    file.getFD().sync();
  }

  public synchronized void close() throws IOException {
    header();
    file.close();
  }

  public static void repair(File path) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(path, "rw")) {
      if (f.length() < 44) return;
      byte[] h = new byte[44];
      f.readFully(h);
      if (h[0] != 'R' || h[8] != 'W' || h[36] != 'd')
        throw new IOException("Not an AudioScope PCM WAV");
      long n = f.length() - 44;
      f.seek(4);
      writeLE(f, (int) (36 + n));
      f.seek(40);
      writeLE(f, (int) n);
    }
  }

  private static void writeLE(RandomAccessFile f, int n) throws IOException {
    f.write(n);
    f.write(n >>> 8);
    f.write(n >>> 16);
    f.write(n >>> 24);
  }

  public static final class Reader implements Closeable {
    public final int rate, channels;
    public final long frames;
    private final RandomAccessFile f;
    private final byte[] cache = new byte[65536];
    private long cacheStart = -1;
    private int cacheLength;

    public Reader(File path) throws IOException {
      f = new RandomAccessFile(path, "r");
      byte[] h = new byte[44];
      f.readFully(h);
      ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
      if (b.getShort(20) != 1 || b.getShort(34) != 16) throw new IOException("PCM16 WAV required");
      channels = b.getShort(22);
      rate = b.getInt(24);
      frames = (f.length() - 44) / (channels * 2);
    }

    public double sample(long frame) throws IOException {
      if (frame < 0 || frame >= frames) return 0;
      long position = frame * channels * 2L;
      if (cacheStart < 0
          || position < cacheStart
          || position + channels * 2 > cacheStart + cacheLength) {
        cacheStart = position;
        f.seek(44 + position);
        cacheLength = f.read(cache);
      }
      int at = (int) (position - cacheStart);
      double v = 0;
      for (int c = 0; c < channels; c++)
        v += (short) ((cache[at + c * 2] & 255) | (cache[at + c * 2 + 1] << 8));
      return v / channels;
    }

    public void close() throws IOException {
      f.close();
    }
  }
}
