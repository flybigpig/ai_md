package android.hardware.led;

import android.hardware.led.ILedCallback;

@VintfStability
interface ILed {
    /** 设置亮度 0..255 */
    void setBrightness(int brightness) = 1;

    /** 读取当前亮度 */
    int getBrightness() = 2;

    /** 注册亮度变化回调 */
    void registerCallback(ILedCallback callback) = 3;
}
