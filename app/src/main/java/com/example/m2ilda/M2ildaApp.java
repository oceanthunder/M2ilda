package com.example.m2ilda;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Single owner of the DJI MSDK lifecycle. The DJI API key is entered by the
 * user in MainActivity and stored in app-private preferences; it is never
 * baked into the APK. Registration is started from MainActivity once a key is
 * available, and the connected product is kept in {@link #getProductInstance()}.
 * Whenever the connection state changes a {@link #FLAG_CONNECTION_CHANGE}
 * broadcast is sent so activities can refresh their UI.
 */
public class M2ildaApp extends Application {

    private static final String TAG = M2ildaApp.class.getName();
    public static final String FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change";
    public static final String FLAG_REGISTRATION_RESULT = "dji_sdk_registration_result";
    public static final String EXTRA_REGISTRATION_ERROR = "registration_error";

    private static final String PREFS_NAME = "m2ilda_prefs";
    private static final String PREF_API_KEY = "dji_api_key";

    private static BaseProduct mProductInstance;
    private static Application mApplication;

    private final AtomicBoolean mRegistrationStarted = new AtomicBoolean(false);

    public static BaseProduct getProductInstance() {
        return mProductInstance;
    }

    public static Application getApplicationInstance() {
        return mApplication;
    }

    /** The DJI API key saved on this device, or "" when the user has not entered one. */
    public String getApiKey() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_API_KEY, "").trim();
    }

    public boolean hasApiKey() {
        return !getApiKey().isEmpty();
    }

    public void saveApiKey(String key) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_API_KEY, key.trim())
                .apply();
    }

    /**
     * Restart the app process. DJISDKManager.setAppKey() ignores calls after the
     * first one in a process, so changing the key requires a fresh process.
     */
    public void restartProcess() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending = PendingIntent.getActivity(this, 100, launch, flags);
            AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarm != null) {
                // Schedule before dying: startActivity() alone would be cancelled
                // with the process before the new activity can initialize.
                alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 200, pending);
            }
        }
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mApplication = this;
        // No auto-registration here: MainActivity prompts for the API key first.
    }

    /**
     * Start DJI SDK registration (idempotent). On success the SDK connects to
     * whatever product is attached/paired (e.g. the drone seen by the RC).
     */
    public void startRegistration() {
        final String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            Log.e(TAG, "startRegistration called without an API key");
            broadcastRegistrationResult("No DJI API key saved");
            return;
        }
        if (!mRegistrationStarted.compareAndSet(false, true)) {
            Log.i(TAG, "Registration already started; ignoring duplicate request");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DJISDKManager sdk = DJISDKManager.getInstance();
                    if (sdk == null) {
                        Log.e(TAG, "DJISDKManager.getInstance() returned null");
                        registrationFailed("SDK manager unavailable");
                        return;
                    }
                    // MSDK normally reads the key from AndroidManifest.xml. Setting
                    // it here lets the released APK ship without any embedded key.
                    DJISDKManager.setAppKey(apiKey);
                    Log.i(TAG, "registerApp() called with device-stored API key");
                    sdk.registerApp(getApplicationContext(), mSDKManagerCallback);
                } catch (Exception e) {
                    Log.e(TAG, "registerApp threw", e);
                    registrationFailed(e.getMessage() == null ? "registration error" : e.getMessage());
                }
            }
        }, "dji-app-register").start();
    }

    private void registrationFailed(String error) {
        mRegistrationStarted.set(false);
        broadcastRegistrationResult(error);
    }

    private void broadcastRegistrationResult(String error) {
        Intent intent = new Intent(FLAG_REGISTRATION_RESULT);
        intent.putExtra(EXTRA_REGISTRATION_ERROR, error == null ? "" : error);
        sendBroadcast(intent);
    }

    private BaseComponent.ComponentListener mDJIComponentListener = new BaseComponent.ComponentListener() {
        @Override
        public void onConnectivityChange(boolean isConnected) {
            Log.d(TAG, "onComponentConnectivityChanged: " + isConnected);
            notifyStatusChange();
        }
    };

    private void notifyStatusChange() {
        Intent localIntent = new Intent(FLAG_CONNECTION_CHANGE);
        localIntent.putExtra("dji_sdk_connection_change", "");
        sendBroadcast(localIntent);
    }

    private void markProduct(BaseProduct product) {
        mProductInstance = product;
        notifyStatusChange();
        Log.i(TAG, "Product set: " + (product == null ? "null" : product.getClass().getSimpleName()));
    }

    private DJISDKManager.SDKManagerCallback mSDKManagerCallback = new DJISDKManager.SDKManagerCallback() {
        @Override
        public void onRegister(DJIError djiError) {
            if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                Log.i(TAG, "SDK Registered Successfully");
                DJISDKManager.getInstance().startConnectionToProduct();
                broadcastRegistrationResult(null);
            } else {
                String description = djiError == null ? "unknown error" : djiError.getDescription();
                Log.e(TAG, "Registration Failed: " + description);
                registrationFailed(description);
            }
            notifyStatusChange();
        }

        @Override
        public void onProductDisconnect() {
            Log.d(TAG, "onProductDisconnect");
            markProduct(null);
        }

        @Override
        public void onProductConnect(BaseProduct baseProduct) {
            Log.d(TAG, "onProductConnect: " + baseProduct);
            markProduct(baseProduct);

            if (baseProduct instanceof Aircraft) {
                Aircraft aircraft = (Aircraft) baseProduct;
                if (aircraft.getFlightController() != null) {
                    aircraft.getFlightController().setComponentListener(mDJIComponentListener);
                }
                if (aircraft.getRemoteController() != null) {
                    aircraft.getRemoteController().setComponentListener(mDJIComponentListener);
                }
            }
        }

        @Override
        public void onProductChanged(BaseProduct baseProduct) {
            Log.d(TAG, "onProductChanged: " + baseProduct);
            markProduct(baseProduct);
        }

        @Override
        public void onComponentChange(BaseProduct.ComponentKey componentKey,
                                      BaseComponent oldComponent,
                                      BaseComponent newComponent) {
            if (newComponent != null) {
                newComponent.setComponentListener(mDJIComponentListener);
            }
            Log.d(TAG, "onComponentChange: " + componentKey);
            notifyStatusChange();
        }

        @Override
        public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) {
            Log.d(TAG, "SDK init progress: " + i);
        }

        @Override
        public void onDatabaseDownloadProgress(long current, long total) {
        }
    };
}
