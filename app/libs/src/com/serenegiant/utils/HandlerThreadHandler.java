package com.serenegiant.utils;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * 等价补齐 serenegiant common 包中的 HandlerThreadHandler。
 * USBMonitor 只用到静态方法 createHandler(String)。
 */
public class HandlerThreadHandler extends Handler {

    public static HandlerThreadHandler createHandler(final String threadName) {
        return createHandler(threadName, null);
    }

    public static HandlerThreadHandler createHandler(final String threadName, final Callback callback) {
        final HandlerThread thread = new HandlerThread(
            threadName != null ? threadName : HandlerThreadHandler.class.getSimpleName());
        thread.start();
        return new HandlerThreadHandler(thread.getLooper(), callback);
    }

    public HandlerThreadHandler() {
        super();
    }

    public HandlerThreadHandler(final Looper looper) {
        super(looper);
    }

    public HandlerThreadHandler(final Looper looper, final Callback callback) {
        super(looper, callback);
    }

    public boolean quit() {
        final Looper looper = getLooper();
        if (looper != null) {
            looper.quit();
            return true;
        }
        return false;
    }
}
