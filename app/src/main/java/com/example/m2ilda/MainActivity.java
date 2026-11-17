package com.example.m2ilda;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKManager;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "M2ilda";

    private TextView mTextConnectionStatus;
    private TextView mTextProduct;
    private TextView mTextFirmware;
    private TextView mTextSdkVersion;
    private TextView mTextIpAddress;
    private TextView mTextCommandLog;
    private ScrollView mCommandLogScroll;
    private Button mBtnRegister;
    private Button mBtnChangeApiKey;
    private Button mBtnBridge;
    private Button mBtnStream;
    private TextView mTextRtmpUrl;
    private Button mBtnEditRtmp;
    private ProgressBar mProgressBar;
    private dji.sdk.sdkmanager.LiveStreamManager mLiveStream;
    private boolean mStreaming;

    private BaseProduct mProduct;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mBridgeStarted;
    private boolean mVideoRelayRegistered;
    private boolean mVideoNotAvailableLogged;

    // Poll product state so the UI always reflects the real connection.
    private final Runnable mUiPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUI();
            maybeRegisterVideoRelay();
            mHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver mConnectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Connection changed broadcast");
            refreshUI();
        }
    };

    private final BroadcastReceiver mRegistrationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String error = intent.getStringExtra(M2ildaApp.EXTRA_REGISTRATION_ERROR);
            if (error == null || error.isEmpty()) {
                appendLog("[sdk] registration successful");
            } else {
                appendLog("[sdk] registration failed: " + error);
                showStatus("DJI registration failed - use CHANGE API KEY");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        refreshUI();
        startBridge();

        IntentFilter filter = new IntentFilter();
        filter.addAction(M2ildaApp.FLAG_CONNECTION_CHANGE);
        filter.addAction(M2ildaApp.FLAG_REGISTRATION_RESULT);
        registerReceiver(mConnectionReceiver, filter);
        registerReceiver(mRegistrationReceiver, filter);

        M2ildaApp app = (M2ildaApp) getApplication();
        if (app.hasApiKey()) {
            appendLog("[sdk] API key loaded from device storage");
            showStatus("Registering DJI SDK...");
            app.startRegistration();
        } else {
            appendLog("[sdk] no DJI API key saved - showing setup dialog");
            showApiKeyDialog(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mUiPollRunnable);
        mHandler.post(mUiPollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mUiPollRunnable);
    }

    private void initUI() {
        mCommandLogScroll = findViewById(R.id.scroll_command_log);
        mTextConnectionStatus = findViewById(R.id.text_connection_status);
        mTextProduct = findViewById(R.id.text_product_info);
        mTextFirmware = findViewById(R.id.text_firmware);
        mTextSdkVersion = findViewById(R.id.text_sdk_version);
        mTextIpAddress = findViewById(R.id.text_ip_address);
        mTextCommandLog = findViewById(R.id.text_command_log);
        mBtnRegister = findViewById(R.id.btn_register);
        mBtnChangeApiKey = findViewById(R.id.btn_change_api_key);
        mBtnBridge = findViewById(R.id.btn_bridge);
        mProgressBar = findViewById(R.id.progress_bar);
        mBtnStream = findViewById(R.id.btn_stream);
        mTextRtmpUrl = findViewById(R.id.text_rtmp_url);
        mBtnEditRtmp = findViewById(R.id.btn_edit_rtmp);

        try {
            DJISDKManager sdk = DJISDKManager.getInstance();
            if (sdk != null) {
                mTextSdkVersion.setText("SDK: " + sdk.getRegistrationSDKVersion()
                        + " | Debug:" + dji.log.GlobalConfig.DEBUG);
            }
        } catch (Exception e) {
            Log.e(TAG, "SDK version display failed", e);
        }

        mBtnRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                M2ildaApp app = (M2ildaApp) getApplication();
                if (!app.hasApiKey()) {
                    showApiKeyDialog(false);
                    return;
                }
                showStatus("Registering (or resuming) DJI SDK...");
                sendBroadcast(new Intent(M2ildaApp.FLAG_CONNECTION_CHANGE));
                app.startRegistration();
            }
        });

        mBtnChangeApiKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showApiKeyDialog(true);
            }
        });

        mBtnBridge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBridge();
            }
        });

        mBtnStream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mStreaming) {
                    stopRtmpStream();
                } else {
                    startRtmpStream();
                }
            }
        });

        mBtnEditRtmp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRtmpDialog();
            }
        });

        mBtnBridge.setEnabled(!mBridgeStarted);
        CommandCenter.getInstance().addLogListener(mCommandLogListener);
    }

    @Override
    protected void onDestroy() {
        CommandCenter.getInstance().removeLogListener(mCommandLogListener);
        try {
            unregisterReceiver(mConnectionReceiver);
            unregisterReceiver(mRegistrationReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private final CommandCenter.LogListener mCommandLogListener = new CommandCenter.LogListener() {
        @Override
        public void onLogLine(final String line) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    appendLog(line);
                }
            });
        }
    };

    private void startBridge() {
        startService(new Intent(this, BridgeService.class));
        BridgeServer.getInstance().start();
        mBridgeStarted = true;
        mBtnBridge.setEnabled(false);
        appendLog("[bridge] started on :5555 (commands) / :5556 (video)");
        showStatus("Bridge started on 5555/5556");
    }

    private void startRtmpStream() {
        try {
            DJISDKManager sdk = DJISDKManager.getInstance();
            if (sdk == null) {
                appendLog("[stream] SDK not ready");
                return;
            }
            mLiveStream = sdk.getLiveStreamManager();
            if (mLiveStream == null) {
                appendLog("[stream] LiveStreamManager unavailable");
                return;
            }
            String url = mTextRtmpUrl.getText().toString().trim();
            if (url.isEmpty()) {
                appendLog("[stream] RTMP URL is empty");
                return;
            }
            mLiveStream.setLiveUrl(url);
            mLiveStream.setLiveVideoResolution(
                    dji.sdk.sdkmanager.LiveVideoResolution.VIDEO_RESOLUTION_1280_720);
            mLiveStream.setLiveVideoBitRateMode(
                    dji.sdk.sdkmanager.LiveVideoBitRateMode.AUTO);
            try {
                sdk.allowStreamWhenAppInBackground(true);
            } catch (Exception ignored) {
            }
            int ret = mLiveStream.startStream();
            mStreaming = true;
            mBtnStream.setText("STOP RTMP STREAM");
            appendLog("[stream] RTMP started -> " + url + " (ret=" + ret + ")");
        } catch (Exception e) {
            appendLog("[stream] start failed: " + e.getMessage());
            Log.e(TAG, "start stream failed", e);
        }
    }

    private void stopRtmpStream() {
        try {
            if (mLiveStream != null) {
                mLiveStream.stopStream();
                appendLog("[stream] RTMP stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "stop stream failed", e);
        }
        mStreaming = false;
        mBtnStream.setText("START RTMP STREAM");
    }

    private void appendLog(final String line) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mTextCommandLog.append(line + "\n");
                int max = 500;
                int count = mTextCommandLog.getLineCount();
                if (count > max) {
                    String text = mTextCommandLog.getText().toString();
                    mTextCommandLog.setText(text.substring(text.indexOf("\n", 0) + 1));
                }
                // Auto-scroll so the most recent line is always visible.
                mCommandLogScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        mCommandLogScroll.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void refreshUI() {
        mTextIpAddress.setText("IP: " + getLocalIpAddress());
        mProduct = M2ildaApp.getProductInstance();
        if (mProduct != null && mProduct.isConnected()) {
            mTextConnectionStatus.setText("Connected");
            mTextConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));

            if (mProduct.getModel() != null) {
                mTextProduct.setText(mProduct.getModel().getDisplayName());
            } else {
                mTextProduct.setText("Unknown Model");
            }

            String version = mProduct.getFirmwarePackageVersion();
            mTextFirmware.setText(version != null && !version.isEmpty()
                    ? "Firmware: " + version : "Firmware: N/A");
        } else if (mProduct instanceof dji.sdk.products.Aircraft) {
            dji.sdk.products.Aircraft aircraft = (dji.sdk.products.Aircraft) mProduct;
            if (aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                mTextConnectionStatus.setText("Only RC Connected");
                mTextConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
                mTextProduct.setText("Waiting for aircraft...");
                mTextFirmware.setText("Firmware: N/A");
            }
        } else {
            mTextConnectionStatus.setText("No Product Connected");
            mTextConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            mTextProduct.setText("Connect your DJI Mavic 2 Enterprise via USB");
            mTextFirmware.setText("Firmware: N/A");
        }
    }

    // Register the FPV video relay once a product is actually connected.
    private void maybeRegisterVideoRelay() {
        BaseProduct product = M2ildaApp.getProductInstance();
        if (product == null || !product.isConnected()) {
            if (!mVideoNotAvailableLogged) {
                mVideoNotAvailableLogged = true;
                appendLog("[video] waiting for aircraft feed...");
            }
            return;
        }
        mVideoNotAvailableLogged = false;
        if (mVideoRelayRegistered) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dji.sdk.camera.VideoFeeder feeder = dji.sdk.camera.VideoFeeder.getInstance();
                    if (feeder == null || feeder.getPrimaryVideoFeed() == null) {
                        return;
                    }
                    dji.sdk.camera.VideoFeeder.VideoFeed feed = feeder.getPrimaryVideoFeed();
                    feed.removeVideoDataListener(mVideoDataListener);
                    feed.addVideoDataListener(mVideoDataListener);
                    mVideoRelayRegistered = true;
                    appendLog("[video] FPV relay registered");
                } catch (Exception e) {
                    Log.e(TAG, "video relay init failed", e);
                }
            }
        }).start();
    }

    private final dji.sdk.camera.VideoFeeder.VideoDataListener mVideoDataListener =
            new dji.sdk.camera.VideoFeeder.VideoDataListener() {
                private long lastLog;

                @Override
                public void onReceive(byte[] videoBuffer, int size) {
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 3000) {
                        lastLog = now;
                        android.util.Log.i("M2ilda", "feed frame: " + size);
                    }
                    BridgeServer.getInstance().broadcastVideo(videoBuffer, size);
                }
            };

    // Returns the controller's current IPv4 address (prefers Wi-Fi, falls back to any interface).
    private String getLocalIpAddress() {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        if (iface.getName() != null && iface.getName().startsWith("wlan")) {
                            return addr.getHostAddress();
                        }
                        if (fallback == null) {
                            fallback = addr.getHostAddress();
                        }
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (Exception ignored) {
        }
        return "not connected";
    }

    /**
     * Small popup for the DJI API key. On first launch it cannot be dismissed
     * without entering a key; later it can be used to change the saved key.
     */
    private void showApiKeyDialog(final boolean changing) {
        final M2ildaApp app = (M2ildaApp) getApplication();
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setText(app.getApiKey());
        input.setSelection(input.getText().length());

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
                .setTitle(changing ? "Change DJI API Key" : "DJI API Key Required")
                .setMessage("Enter the App Key from developer.dji.com. It must be registered "
                        + "for package com.example.m2ilda. The first registration requires "
                        + "an internet connection.")
                .setView(container)
                .setPositiveButton("OK", null);
        if (changing) {
            builder.setNegativeButton("Cancel", null);
        } else {
            builder.setCancelable(false);
        }

        final android.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String key = input.getText().toString().trim();
                                if (key.isEmpty()) {
                                    showStatus("API key cannot be empty");
                                    return; // keep the dialog open
                                }
                                String oldKey = app.getApiKey();
                                app.saveApiKey(key);
                                if (changing && !key.equals(oldKey)) {
                                    appendLog("[sdk] API key updated - restarting app");
                                    app.restartProcess();
                                } else if (changing) {
                                    appendLog("[sdk] API key unchanged - use REGISTER SDK to retry");
                                } else {
                                    appendLog("[sdk] API key saved on device");
                                    showStatus("Registering DJI SDK...");
                                    app.startRegistration();
                                }
                                dialog.dismiss();
                            }
                        });
            }
        });
        dialog.show();
    }

    private void showRtmpDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(mTextRtmpUrl.getText().toString());
        input.setSelection(input.getText().length());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Enter RTMP Address")
                .setView(container)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        if (!url.isEmpty()) {
                            mTextRtmpUrl.setText(url);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showStatus(final String msg) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}