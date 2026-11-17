package com.example.m2ilda;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dji.common.error.DJIError;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.flightcontroller.FlightController;

/**
 * Executes webui.py commands on the connected aircraft/camera and keeps an
 * in-app command log (the "command center"). Also pushes telemetry and command
 * echoes back to the RC screen via the BridgeServer broadcast.
 */
public class CommandCenter {

    private static final String TAG = "CommandCenter";

    private static final float STICK_SPEED = 4.0f;      // m/s (Mavic 2 max ~9 m/s)
    private static final float ROTATE_SPEED = 50.0f;    // deg/s (Mavic 2 max ~90 deg/s)
    private static final int MOVE_HOLD_MS = 350;        // duration of a translation nudge
    private static final int ROTATE_HOLD_MS = 900;      // duration of a yaw nudge
    private static final int STICK_FRAME_MS = 40;       // 25 Hz virtual-stick refresh

    private static CommandCenter instance;

    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService stickExec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean stickModeOn;
    private volatile boolean stickEnabling;
    private volatile int stickGen;

    /** Virtual-stick frames are silent; only the final enable/disable is logged. */
    private static final dji.common.util.CommonCallbacks.CompletionCallback<DJIError> SILENT =
            new dji.common.util.CommonCallbacks.CompletionCallback<DJIError>() {
                @Override
                public void onResult(DJIError error) {
                }
            };

    public interface LogListener {
        void onLogLine(String line);
    }

    static synchronized CommandCenter getInstance() {
        if (instance == null) {
            instance = new CommandCenter();
        }
        return instance;
    }

    public void addLogListener(LogListener l) {
        logListeners.add(l);
    }

    public void removeLogListener(LogListener l) {
        logListeners.remove(l);
    }

    void logLine(final String line) {
        Log.i(TAG, "LOG: " + line);
        for (LogListener l : logListeners) {
            try {
                l.onLogLine(line);
            } catch (Exception ignored) {
            }
        }
    }
    private void broadcast(final String line) {
        BridgeServer.getInstance().broadcastLine(line);
    }

    /** Execute one webui command line. Returns the reply to send (or null). */
    public String execute(String cmd) {
        String raw = cmd.trim();

        // Mission commands carry a base64 payload; route them before the
        // lowercasing/trim below so the payload isn't mangled.
        String head = raw.toLowerCase();
        if (head.equals("mission") || head.startsWith("mission ")
                || head.startsWith("takeoff_run")) {
            return WaypointMissionManager.getInstance().handle(raw);
        }

        String c = raw.toLowerCase();

        // "status" is the webui telemetry poller (every ~2.5 s); log it out of
        // band so it doesn't flood the command center but telemetry still flows.
        if (c.equals("status")) {
            sendStatus();
            return "OK:status";
        }

        logLine("[CMD] " + c);

        switch (c) {
            case "help":
                return "OK:takeoff land up down fwd back left right rotl rotr photo rec stop status gohome mission";
            case "takeoff":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        final FlightController fc = flightController();
                        if (fc == null) {
                            reply("ERR:no flight controller");
                            return;
                        }
                        fc.startTakeoff(new Callback("takeoff"));
                    }
                });
                return null;
            case "land":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        final FlightController fc = flightController();
                        if (fc == null) {
                            reply("ERR:no flight controller");
                            return;
                        }
                        fc.startLanding(new Callback("land"));
                    }
                });
                return null;
            case "gohome":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        final FlightController fc = flightController();
                        if (fc == null) {
                            reply("ERR:no flight controller");
                            return;
                        }
                        fc.startGoHome(new Callback("gohome"));
                    }
                });
                return null;
            case "photo":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Camera camera = camera();
                        if (camera == null) {
                            reply("ERR:no camera");
                            return;
                        }
                        camera.setMode(dji.common.camera.SettingsDefinitions.CameraMode.SHOOT_PHOTO, new Callback("photo.setMode") {
                            @Override
                            public void onResult(DJIError error) {
                                if (error == null) {
                                    camera.setShootPhotoMode(dji.common.camera.SettingsDefinitions.ShootPhotoMode.SINGLE,
                                            new Callback("photo.single") {
                                                @Override
                                                public void onResult(DJIError e2) {
                                                    if (e2 == null) {
                                                        camera.startShootPhoto(new Callback("photo.shoot"));
                                                    }
                                                }
                                            });
                                }
                            }
                        });
                    }
                });
                return null;
            case "rec":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Camera camera = camera();
                        if (camera == null) {
                            reply("ERR:no camera");
                            return;
                        }
                        camera.setMode(dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO, new Callback("rec.setMode") {
                            @Override
                            public void onResult(DJIError error) {
                                if (error == null) {
                                    camera.startRecordVideo(new Callback("rec.start"));
                                }
                            }
                        });
                    }
                });
                return null;
            case "stop":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Camera camera = camera();
                        if (camera == null) {
                            reply("ERR:no camera");
                            return;
                        }
                        camera.setMode(dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO, new Callback("stop.setMode") {
                            @Override
                            public void onResult(DJIError error) {
                                if (error == null) {
                                    camera.stopRecordVideo(new Callback("stop.rec"));
                                }
                            }
                        });
                    }
                });
                return null;
            case "up":
                stick(0, 0, STICK_SPEED, 0, MOVE_HOLD_MS);
                return null;
            case "down":
                stick(0, 0, -STICK_SPEED, 0, MOVE_HOLD_MS);
                return null;
            case "fwd":
                stick(0, STICK_SPEED, 0, 0, MOVE_HOLD_MS);
                return null;
            case "back":
                stick(0, -STICK_SPEED, 0, 0, MOVE_HOLD_MS);
                return null;
            case "left":
                stick(-STICK_SPEED, 0, 0, 0, MOVE_HOLD_MS);
                return null;
            case "right":
                stick(STICK_SPEED, 0, 0, 0, MOVE_HOLD_MS);
                return null;
            case "rotl":
                stick(0, 0, 0, -ROTATE_SPEED, ROTATE_HOLD_MS);
                return null;
            case "rotr":
                stick(0, 0, 0, ROTATE_SPEED, ROTATE_HOLD_MS);
                return null;
            case "stopmovement":
            case "hover":
                stick(0, 0, 0, 0, 120);
                return null;
            default:
                return "ERR:unknown command '" + cmd + "'";
        }
    }

    // ---------------------------------------------------------------------
    // Virtual-stick movement.
    //
    // DJI's SDK constructor is FlightControlData(pitch, roll, yaw, throttle).
    // The public stick() helper takes (roll, pitch, throttle, yaw) for the
    // command table's readability and maps the axes here.
    //
    // A single control frame is ignored by the flight controller: the FC
    // expects a fresh frame every ~40 ms while a movement is active and
    // falls back to hover when the stream stops. So a "nudge" enables
    // virtual stick mode, streams frames for holdMs at 25 Hz, then sends a
    // zero frame and disables the mode again.
    // ---------------------------------------------------------------------

    private void stick(final float roll, final float pitch, final float throttle,
                       final float yaw, final int holdMs) {
        final int gen = ++stickGen;
        final float r = roll, p = pitch, t = throttle, y = yaw;
        final int ms = holdMs;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final FlightController fc = flightController();
                if (fc == null) {
                    reply("ERR:no flight controller");
                    return;
                }
                if (stickModeOn) {
                    streamStick(fc, r, p, t, y, ms, gen);
                } else if (!stickEnabling) {
                    stickEnabling = true;
                    enableStickMode(fc, true, new Runnable() {
                        @Override
                        public void run() {
                            stickEnabling = false;
                            if (gen == stickGen) {
                                streamStick(fc, r, p, t, y, ms, gen);
                            } else {
                                disableStick(fc);
                            }
                        }
                    });
                } else {
                    // Another command is still enabling virtual stick mode.
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            stick(r, p, t, y, ms);
                        }
                    }, 100);
                }
            }
        });
    }

    private void streamStick(final FlightController fc, final float roll, final float pitch,
                             final float throttle, final float yaw, final int holdMs,
                             final int gen) {
        stickExec.execute(new Runnable() {
            @Override
            public void run() {
                final long end = System.currentTimeMillis() + holdMs;
                while (gen == stickGen && System.currentTimeMillis() < end) {
                    fc.sendVirtualStickFlightControlData(
                            new FlightControlData(pitch, roll, yaw, throttle), SILENT);
                    try {
                        Thread.sleep(STICK_FRAME_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                // Only the latest movement owns the finish; an older stream
                // simply stops when a newer command takes over.
                if (gen == stickGen) {
                    fc.sendVirtualStickFlightControlData(
                            new FlightControlData(0, 0, 0, 0), SILENT);
                    disableStick(fc);
                }
            }
        });
    }

    private void enableStickMode(final FlightController fc, final boolean on, final Runnable next) {
        // Control modes must be configured before virtual stick data is sent.
        if (on) {
            fc.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
            fc.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
            fc.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
            fc.setVerticalControlMode(VerticalControlMode.VELOCITY);
        }
        fc.setVirtualStickModeEnabled(on, new Callback("stickMode") {
            @Override
            public void onResult(DJIError error) {
                if (error != null) {
                    logLine("[ERR] stickMode: " + error.getDescription());
                    broadcast("ERR:stickMode: " + error.getDescription());
                    stickEnabling = false;
                    return;
                }
                stickModeOn = on;
                if (next != null) {
                    next.run();
                }
            }
        });
    }

    private void disableStick(FlightController fc) {
        if (!stickModeOn) {
            return;
        }
        stickModeOn = false;
        fc.setVirtualStickModeEnabled(false, new Callback("stickMode off") {
        });
    }

    private void sendStatus() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                BaseProduct product = M2ildaApp.getProductInstance();
                boolean connected = product != null && product.isConnected();
                StringBuilder sb = new StringBuilder("T:");
                sb.append("connected=").append(connected);
                if (connected) {
                    try {
                        dji.common.flightcontroller.FlightControllerState st =
                                flightController().getState();
                        if (st != null) {
                            sb.append(";alt=").append(String.format("%.1f",
                                    st.getUltrasonicHeightInMeters() > 0
                                            ? st.getUltrasonicHeightInMeters()
                                            : (st.getAircraftLocation() != null
                                                    ? st.getAircraftLocation().getAltitude() : 0)));
                            sb.append(";sats=").append(st.getSatelliteCount());
                            sb.append(";speed=").append(String.format("%.1f",
                                    Math.hypot(st.getVelocityX(), st.getVelocityY())));
                            sb.append(";yaw=").append(String.format("%.0f", st.getAttitude().yaw));
                            sb.append(";pitch=").append(String.format("%.0f", st.getAttitude().pitch));
                            sb.append(";roll=").append(String.format("%.0f", st.getAttitude().roll));
                            sb.append(";flying=").append(st.isFlying());
                            dji.common.flightcontroller.LocationCoordinate3D loc =
                                    st.getAircraftLocation();
                            if (loc != null) {
                                sb.append(";lat=").append(String.format("%.6f", loc.getLatitude()));
                                sb.append(";lon=").append(String.format("%.6f", loc.getLongitude()));
                            }
                            dji.common.model.LocationCoordinate2D home = st.getHomeLocation();
                            if (home != null) {
                                sb.append(";hlat=").append(String.format("%.6f", home.getLatitude()));
                                sb.append(";hlon=").append(String.format("%.6f", home.getLongitude()));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "telemetry", e);
                    }
                }
                broadcast(sb.toString());
            }
        });
    }

    private void reply(final String line) {
        broadcast(line);
    }

    private FlightController flightController() {
        BaseProduct product = M2ildaApp.getProductInstance();
        if (product != null && product.isConnected()
                && product instanceof dji.sdk.products.Aircraft) {
            return ((dji.sdk.products.Aircraft) product).getFlightController();
        }
        return null;
    }

    private Camera camera() {
        BaseProduct product = M2ildaApp.getProductInstance();
        if (product != null && product.isConnected()) {
            Camera c = product.getCamera();
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private class Callback implements dji.common.util.CommonCallbacks.CompletionCallback<DJIError> {
        private final String what;

        Callback(String what) {
            this.what = what;
        }

        @Override
        public void onResult(DJIError error) {
            if (error == null) {
                logLine("[OK] " + what);
                broadcast("OK:" + what);
            } else {
                logLine("[ERR] " + what + ": " + error.getDescription());
                broadcast("ERR:" + what + ": " + error.getDescription());
            }
        }
    }
}