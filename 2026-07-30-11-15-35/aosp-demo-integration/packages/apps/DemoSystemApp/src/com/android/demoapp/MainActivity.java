package com.android.demoapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.demo.DemoManager;
import android.os.demo.IDemoManagerCallback;
import android.util.Log;
import android.widget.TextView;

/**
 * 系统 App 演示:
 *   1. getSystemService(DemoManager.class) 拿到 Framework 系统服务客户端;
 *   2. 通过 DemoManager 调用 DemoManagerService,后者转发到 HAL;
 *   3. 注册回调接收 HAL 主动上报事件。
 */
public class MainActivity extends Activity {
    private static final String TAG = "DemoSystemApp";
    private TextView mText;

    private final IDemoManagerCallback mCb = new IDemoManagerCallback.Stub() {
        @Override
        public void onEvent(int code, String msg) {
            runOnUiThread(() -> append("\n[HAL 上报] code=" + code + ", msg=" + msg));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText("Demo System App\n");
        mText = tv;
        setContentView(tv);

        DemoManager demo = getSystemService(DemoManager.class);
        if (demo == null) {
            append("DemoManager 不可用!请确认 system_server 已注册 demo 服务且 SELinux 放行。\n");
            return;
        }

        int before = demo.getCount();
        demo.setCount(before + 1);
        int after = demo.getCount();
        demo.registerCallback(mCb);

        append("getCount(前)=" + before + "\n");
        append("setCount(" + (before + 1) + ")\n");
        append("getCount(后)=" + after + "\n");
        append("已注册 HAL 上报回调,等待事件...\n");
        Log.i(TAG, "demo getCount=" + after);
    }

    private void append(String s) {
        mText.append(s);
    }
}
