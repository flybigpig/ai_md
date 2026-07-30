package android.hardware.demo;

@VintfStability
interface IDemo {
    /** 读取 HAL 内部维护的计数值 */
    int getCount();

    /** 写入计数值,返回处理结果 */
    DemoStatus setValue(int value);

    /** 注册回调,HAL 可主动上报事件给 Framework 层 */
    void setCallback(in IDemoCallback cb);
}
