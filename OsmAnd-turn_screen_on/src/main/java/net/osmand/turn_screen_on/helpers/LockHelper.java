package net.osmand.turn_screen_on.helpers;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.PowerManager;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import net.osmand.turn_screen_on.receiver.DeviceAdminRecv;

public class LockHelper {
    private PowerManager.WakeLock wakeLock = null;
    private DevicePolicyManager mDevicePolicyManager;
    private ComponentName mDeviceAdmin;
    private Handler uiHandler;
    private Context context;
    private KeyguardManager.KeyguardLock keyguardLock;
    private LockRunnable lockRunnable;

    private final static String TAG = "LockHelperTag";

    public LockHelper(Context context) {
        this.context = context;
        uiHandler = new Handler();
        mDeviceAdmin = new ComponentName(context, DeviceAdminRecv.class);
        mDevicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        lockRunnable = new LockRunnable();
        keyguardLock = ((KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE))
                .newKeyguardLock(TAG);
    }

    private void releaseWakeLocks() {
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private class LockRunnable implements Runnable {
        @Override
        public void run() {
            lock();
        }
    }

    public void lock() {
        if (readyToLock()) {
            releaseWakeLocks();
            keyguardLock.reenableKeyguard();
            mDevicePolicyManager.lockNow();

            Log.d("ttpl", "LockHelper: device lock");
        }
    }

    public void unlock() {
        if (readyToUnlock()) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "tso:wakelocktag");
            wakeLock.acquire();
            keyguardLock.disableKeyguard();

            Log.d("ttpl", "LockHelper: device unlock");
        }
    }

    public void timedUnlock(long millis) {
        uiHandler.removeCallbacks(lockRunnable);
        unlock();
        uiHandler.postDelayed(lockRunnable, millis);
    }

    private boolean readyToLock() {
        return mDevicePolicyManager != null
                && mDeviceAdmin != null
                && mDevicePolicyManager.isAdminActive(mDeviceAdmin)
                && ContextCompat.checkSelfPermission(context, Manifest.permission.DISABLE_KEYGUARD)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean readyToUnlock() {
        return wakeLock==null
                && ContextCompat.checkSelfPermission(context, Manifest.permission.DISABLE_KEYGUARD)
                == PackageManager.PERMISSION_GRANTED;
    }
}
