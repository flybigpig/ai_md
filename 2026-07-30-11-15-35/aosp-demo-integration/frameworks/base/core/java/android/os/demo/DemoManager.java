package android.os.demo;

import android.annotation.SystemService;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * 对外暴露的系统服务客户端。
 * APP 通过 {@code context.getSystemService(Context.DEMO_SERVICE)} 或
 * {@code context.getSystemService(DemoManager.class)} 获取实例。
 *
 * 内部最终调用到 HAL(android.hardware.demo.IDemo/default)。
 */
@SystemService(Context.DEMO_SERVICE)
public class DemoManager {
    private static final String TAG = "DemoManager";
    private final Context mContext;
    private final IDemoManager mService;

    /** @hide */
    public DemoManager(Context context, IDemoManager service) {
        mContext = context;
        mService = service;
    }

    /** 读取 HAL 计数值 */
    public int getCount() {
        try {
            return mService.getCount();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** 写入 HAL 计数值 */
    public void setCount(int value) {
        try {
            mService.setCount(value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** 注册回调,接收 HAL 主动上报的事件 */
    public void registerCallback(IDemoManagerCallback cb) {
        try {
            mService.registerCallback(cb);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
