package com.android.server.demo;

import android.content.Context;
import android.hardware.demo.DemoStatus;
import android.hardware.demo.IDemo;
import android.hardware.demo.IDemoCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行在 system_server 进程中的 Java 系统服务。
 * 职责:
 *   1. 向 ServiceManager 注册 Binder 服务 "demo"(供 APP 通过 DemoManager 访问);
 *   2. 作为客户端连接 HAL(android.hardware.demo.IDemo/default),把 APP 请求转发给 HAL;
 *   3. 把 HAL 通过 IDemoCallback 上报的事件,转发给 APP 注册的 IDemoManagerCallback。
 */
public class DemoManagerService extends SystemService {
    private static final String TAG = "DemoManagerService";
    private static final String HAL_INSTANCE = IDemo.DESCRIPTOR + "/default";

    private final Context mContext;
    private volatile IDemo mHal;
    private final Object mLock = new Object();
    private final List<IDemoManagerCallback> mCallbacks = new ArrayList<>();

    public DemoManagerService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DEMO_SERVICE, mBinder);
        connectHal();
        Log.i(TAG, "DemoManagerService started, hal=" + (mHal != null));
    }

    /** 连接 HAL;HAL 可能晚于 system_server 启动,这里用 waitForService 阻塞等待 */
    private void connectHal() {
        try {
            IBinder binder = ServiceManager.waitForService(HAL_INSTANCE);
            if (binder == null) {
                Log.w(TAG, "HAL binder null: " + HAL_INSTANCE);
                return;
            }
            mHal = IDemo.Stub.asInterface(binder);
            mHal.setCallback(mHalCallback);
            Log.i(TAG, "HAL connected: " + HAL_INSTANCE);
        } catch (RemoteException e) {
            Log.e(TAG, "connectHal setCallback failed", e);
        }
    }

    /** HAL -> Framework:HAL 上报事件后,转发给所有 APP 回调 */
    private final IDemoCallback mHalCallback = new IDemoCallback.Stub() {
        @Override
        public void onEvent(int code, String msg) {
            synchronized (mLock) {
                for (IDemoManagerCallback cb : mCallbacks) {
                    try {
                        cb.onEvent(code, msg);
                    } catch (RemoteException e) {
                        Log.w(TAG, "forward callback dead", e);
                    }
                }
            }
        }
    };

    /** APP -> Framework:实现 IDemoManager Binder 接口 */
    private final IDemoManager.Stub mBinder = new IDemoManager.Stub() {
        @Override
        public int getCount() {
            enforceDemoPermission();
            IDemo hal = mHal;
            if (hal == null) return -1;
            try {
                return hal.getCount();
            } catch (RemoteException e) {
                Log.e(TAG, "getCount HAL fail", e);
                return -1;
            }
        }

        @Override
        public void setCount(int value) {
            enforceDemoPermission();
            IDemo hal = mHal;
            if (hal == null) return;
            try {
                DemoStatus s = hal.setValue(value);
                Log.i(TAG, "HAL setValue -> ok=" + s.ok + ", desc=" + s.description);
            } catch (RemoteException e) {
                Log.e(TAG, "setCount HAL fail", e);
            }
        }

        @Override
        public void registerCallback(IDemoManagerCallback cb) {
            enforceDemoPermission();
            synchronized (mLock) {
                mCallbacks.add(cb);
            }
        }
    };

    /** 仅允许持有 android.permission.ACCESS_DEMO_SERVICE 的调用方(platform 签名系统 app) */
    private void enforceDemoPermission() {
        mContext.enforceCallingPermission(
                "android.permission.ACCESS_DEMO_SERVICE",
                "DemoManagerService");
    }
}
