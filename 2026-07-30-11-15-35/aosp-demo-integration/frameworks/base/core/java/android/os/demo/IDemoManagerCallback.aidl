package android.os.demo;

/** APP 侧回调,Framework 服务把 HAL 上报事件转发到这里 */
oneway interface IDemoManagerCallback {
    void onEvent(int code, String msg);
}
