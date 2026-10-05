package hev.htproxy
object TProxyService {
  init { System.loadLibrary("hev-socks5-tunnel") }
  @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
  @JvmStatic external fun TProxyStopService()
  // hev ki JNI_OnLoad ye teeno methods register karti hai; ye na ho to NoSuchMethodError -> app crash
  @JvmStatic external fun TProxyGetStats(): LongArray
}
