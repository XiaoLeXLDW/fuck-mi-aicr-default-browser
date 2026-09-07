package android.os;
/** Test-only transport; never compiled into the APK. */
public interface IBinder {
    boolean pingBinder();
    default boolean isBinderAlive() { return pingBinder(); }
}
