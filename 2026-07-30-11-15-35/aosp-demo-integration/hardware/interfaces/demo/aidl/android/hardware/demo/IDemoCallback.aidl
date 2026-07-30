package android.hardware.demo;

@VintfStability
oneway interface IDemoCallback {
    /** HAL 主动上报事件,code 为事件类型,msg 为描述 */
    void onEvent(int code, String msg);
}
