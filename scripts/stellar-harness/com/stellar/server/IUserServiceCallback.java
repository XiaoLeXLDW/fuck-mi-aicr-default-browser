package com.stellar.server;
import android.os.IBinder;
public interface IUserServiceCallback {
    void onServiceConnected(IBinder binder, String verification);
    void onServiceDisconnected();
    void onServiceStartFailed(int code, String message);
    abstract class Stub implements IUserServiceCallback { }
}
