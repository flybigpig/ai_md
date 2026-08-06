// frameworks/base/core/java/android/hello/HelloManager.java
// @hide 门面：App 经 getSystemService 拿到，内部走 IHelloService
package android.hello;

import android.annotation.RequiresPermission;
import android.os.IHelloService;
import android.os.RemoteException;

public class HelloManager {
    private final IHelloService mService;

    public HelloManager(IHelloService service) {
        mService = service;
    }

    @RequiresPermission(android.Manifest.permission.HELLO_ACCESS)   // 权限闸门
    public String getHello() {
        try {
            return mService.getHello();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(android.Manifest.permission.HELLO_ACCESS)
    public void setHello(String msg) {
        try {
            mService.setHello(msg);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
