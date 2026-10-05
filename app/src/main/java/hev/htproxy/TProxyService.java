package hev.htproxy;

// Upstream (hev-socks5-tunnel) jaisi hi plain Java class: static native methods.
// Build ke waqt CI is file ko hev ki asli JNI table se dobara bana sakta hai.
public class TProxyService {
  static { System.loadLibrary("hev-socks5-tunnel"); }
  public static native void TProxyStartService(String configPath, int fd);
  public static native void TProxyStopService();
  public static native long[] TProxyGetStats();
}
