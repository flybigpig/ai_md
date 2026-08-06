// frameworks/base/services/core/java/com/android/server/hello/HelloService.java
// system_server 侧服务：包裹 HAL，对 App 暴露 IHelloService
package com.android.server.hello;

import android.content.Context;
import android.hardware.hello.IHello;                 // HAL AIDL 的 java 后端
import android.os.IBinder;
import android.os.IHelloService;                      // 本服务的 AIDL
import android.os.RemoteException;
import android.os.ServiceManager;
import com.android.server.SystemService;

public class HelloService extends SystemService {
    private final IHelloService.Stub mStub = new IHelloService.Stub() {
        @Override
        public String getHello() throws RemoteException {
            IHello hal = getHal();                     // 懒取 HAL 代理
            if (hal == null) throw new RemoteException("HAL not ready");
            return hal.getHello();                     // 第二道 Binder：调 HAL
        }

        @Override
        public void setHello(String msg) throws RemoteException {
            IHello hal = getHal();
            if (hal == null) throw new RemoteException("HAL not ready");
            hal.setHello(msg);
        }
    };

    private IHello mHal;
    // HAL 死后置空，下次 getHal() 自动重连（修 🔴 阻塞 boot + DeadObject）
    private final IBinder.DeathRecipient mDeath = () -> { mHal = null; };

    public HelloService(Context ctx) {
        super(ctx);
    }

    @Override
    public void onStart() {
        // 只注册 framework service，绝不在构造里 waitForService(HAL)
        publishBinderService(Context.HELLO_SERVICE, mStub);
    }

    // 懒连接：用到才取 HAL 代理，HAL 没起也不阻塞整机启动
    private IHello getHal() {
        if (mHal == null) {
            IBinder b = ServiceManager.getService("android.hardware.hello.IHello/default");
            if (b != null) {
                mHal = IHello.Stub.asInterface(b);     // AIDL stable：asInterface
                try { mHal.asBinder().linkToDeath(mDeath, 0); } catch (RemoteException ignored) {}
            }
        }
        return mHal;
    }
}
