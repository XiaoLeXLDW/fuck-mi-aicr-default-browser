package com.stellar.server;
import android.os.*;
public interface IStellarService {
    String startUserService(Bundle args, IUserServiceCallback callback) throws RemoteException;
    void stopUserService(String token) throws RemoteException;
    default IBinder asBinder() { return null; }
}
