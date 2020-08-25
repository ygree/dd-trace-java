package datadog.trace.mlt;

public final class SessionData {
  private final byte[] blob;
  private final int sampleCount;

  public SessionData(byte[] blob, int sampleCount) {
    this.blob = blob;
    this.sampleCount = sampleCount;
  }

  public byte[] getBlob() {
    return blob;
  }

  public int getSampleCount() {
    return sampleCount;
  }
}
