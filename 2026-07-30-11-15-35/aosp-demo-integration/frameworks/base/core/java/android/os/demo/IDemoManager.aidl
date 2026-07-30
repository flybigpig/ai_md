package android.os.demo;

import android.os.demo.IDemoManagerCallback;

/** APP 侧看到的 Binder 接口,Framework 服务 DemoManagerService 实现它 */
interface IDemoManager {
    int getCount();
    void setCount(int value);
    void registerCallback(IDemoManagerCallback cb);
}
