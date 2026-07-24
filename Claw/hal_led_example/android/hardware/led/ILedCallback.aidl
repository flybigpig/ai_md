package android.hardware.led;

@VintfStability
interface ILedCallback {
    void onBrightnessChanged(int brightness) = 1;
}
