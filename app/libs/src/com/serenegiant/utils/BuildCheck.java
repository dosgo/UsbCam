package com.serenegiant.utils;

import android.os.Build;

/**
 * 等价补齐 serenegiant common 包中的 BuildCheck。
 * USBMonitor 只用到 isLollipop / isAndroid5 / isMarshmallow。
 */
public class BuildCheck {

    public static boolean isLollipop() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static boolean isAndroid5() {
        return isLollipop();
    }

    public static boolean isMarshmallow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
}
