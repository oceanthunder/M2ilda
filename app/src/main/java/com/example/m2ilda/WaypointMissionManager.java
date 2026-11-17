package com.example.m2ilda;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dji.common.error.DJIError;
import dji.common.mission.waypoint.Waypoint;
import dji.common.mission.waypoint.WaypointAction;
import dji.common.mission.waypoint.WaypointActionType;
import dji.common.mission.waypoint.WaypointExecutionProgress;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionDownloadEvent;
import dji.common.mission.waypoint.WaypointMissionExecutionEvent;
import dji.common.mission.waypoint.WaypointMissionFinishedAction;
import dji.common.mission.waypoint.WaypointMissionFlightPathMode;
import dji.common.mission.waypoint.WaypointMissionHeadingMode;
import dji.common.mission.waypoint.WaypointMissionState;
import dji.common.mission.waypoint.WaypointMissionUploadEvent;
import dji.common.mission.waypoint.WaypointUploadProgress;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Wraps the DJI Waypoint Mission engine so the PC web UI can upload and run
 * waypoint missions over the plain-text bridge.
 *
 * The bridge protocol is newline-delimited text, so mission payloads arrive as
 * base64-encoded JSON on one line:
 *
 *   mission upload &lt;b64&gt;       validate + upload the mission
 *   mission run    &lt;b64&gt;       upload then start on success
 *   takeoff_run    &lt;b64&gt;       upload, take off, wait until airborne, start
 *   mission start|pause|resume|stop|clear
 *   mission state
 *
 * Progress is broadcast back to every bridge client as a "MISSION:..." line.
 */
public class WaypointMissionManager {

    private static final String TAG = "WaypointMission";

    private static final float MIN_SPEED = 2.0f;
    private static final float MAX_SPEED = 15.0f;

    private static WaypointMissionManager instance;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

    private volatile WaypointMissionOperator operator;
    private volatile boolean listenerRegistered;

    // Last execution progress, replayed for "mission state" queries.
    private volatile int lastIdx = 0;
    private volatile int lastTotal = 0;
    private volatile boolean lastReached = false;
    private volatile boolean lastUploaded = false;

    static synchronized WaypointMissionManager getInstance() {
        if (instance == null) {
            instance = new WaypointMissionManager();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // Command entry point
    // ------------------------------------------------------------------

    /** Handle a "mission ..." or "takeoff_run ..." line. */
    public String handle(String raw) {
        String[] p = raw.trim().split("\\s+", 3);
        String head = p[0].toLowerCase();
        String action = p.length > 1 ? p[1].toLowerCase() : "state";
        String payload = p.length > 2 ? p[2] : null;

        if (head.equals("takeoff_run")) {
            if (payload == null) {
                return "ERR:mission: missing payload";
            }
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    upload(payload, true, true);
                }
            });
            return "OK:mission.takeoff_run";
        }

        switch (action) {
            case "upload":
                if (payload == null) {
                    return "ERR:mission: missing payload";
                }
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        upload(payload, false, false);
                    }
                });
                return "OK:mission.upload";
            case "run":
                if (payload == null) {
                    return "ERR:mission: missing payload";
                }
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        upload(payload, true, false);
                    }
                });
                return "OK:mission.run";
            case "start":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        start();
                    }
                });
                return "OK:mission.start";
            case "pause":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        pause();
                    }
                });
                return "OK:mission.pause";
            case "resume":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        resume();
                    }
                });
                return "OK:mission.resume";
            case "stop":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        stop();
                    }
                });
                return "OK:mission.stop";
            case "clear":
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        clear();
                    }
                });
                return "OK:mission.clear";
            case "state":
            default:
                return stateLine();
        }
    }

    // ------------------------------------------------------------------
    // Mission lifecycle
    // ------------------------------------------------------------------

    private void upload(final String b64, final boolean autoStart, final boolean takeoffFirst) {
        final WaypointMissionOperator op = operator();
        if (op == null) {
            emit("ERR:mission: waypoint operator unavailable (is a product connected?)");
            return;
        }
        final WaypointMission mission;
        try {
            mission = buildMission(b64);
        } catch (Exception e) {
            emit("ERR:mission.build: " + e.getMessage());
            Log.e(TAG, "build failed", e);
            return;
        }

        DJIError loadErr = op.loadMission(mission);
        if (loadErr != null) {
            emit("ERR:mission.load: " + loadErr.getDescription());
            return;
        }
        lastTotal = mission.getWaypointCount();
        lastIdx = 0;
        lastReached = false;
        lastUploaded = false;
        emit("OK:mission.load");
        emit("MISSION:state=" + name(op.getCurrentState()) + ";phase=load;idx=0;total="
                + lastTotal + ";done=false");

        op.uploadMission(new CommonCallbacks.CompletionCallback<DJIError>() {
            @Override
            public void onResult(DJIError error) {
                if (error != null) {
                    // One automatic retry, then give up (DJI's own sample does this).
                    Log.w(TAG, "upload failed: " + error.getDescription());
                    emit("[mission] upload failed (" + error.getDescription() + "), retrying...");
                    op.retryUploadMission(new CommonCallbacks.CompletionCallback<DJIError>() {
                        @Override
                        public void onResult(DJIError error2) {
                            if (error2 != null) {
                                emit("ERR:mission.upload: " + error2.getDescription());
                                emit("MISSION:state=" + name(op.getCurrentState())
                                        + ";phase=upload;err=1;done=true");
                                return;
                            }
                            onUploaded(op, autoStart, takeoffFirst);
                        }
                    });
                    return;
                }
                onUploaded(op, autoStart, takeoffFirst);
            }
        });
    }

    private void onUploaded(WaypointMissionOperator op, boolean autoStart, boolean takeoffFirst) {
        lastUploaded = true;
        emit("OK:mission.upload");
        emit("MISSION:state=" + name(op.getCurrentState()) + ";phase=upload;done=true");
        if (takeoffFirst) {
            takeoffThenStart();
        } else if (autoStart) {
            start();
        }
    }

    private void start() {
        final WaypointMissionOperator op = operator();
        if (op == null) {
            emit("ERR:mission: waypoint operator unavailable");
            return;
        }
        op.startMission(new CommonCallbacks.CompletionCallback<DJIError>() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    emit("OK:mission.start");
                } else {
                    emit("ERR:mission.start: " + error.getDescription());
                }
            }
        });
    }

    private void pause() {
        WaypointMissionOperator op = operator();
        if (op == null) {
            emit("ERR:mission: waypoint operator unavailable");
            return;
        }
        op.pauseMission(new CommonCallbacks.CompletionCallback<DJIError>() {
            @Override
            public void onResult(DJIError error) {
                emit(error == null ? "OK:mission.pause" : "ERR:mission.pause: " + error.getDescription());
            }
        });
    }

    private void resume() {
        WaypointMissionOperator op = operator();
        if (op == null) {
            emit("ERR:mission: waypoint operator unavailable");
            return;
        }
        op.resumeMission(new CommonCallbacks.CompletionCallback<DJIError>() {
            @Override
            public void onResult(DJIError error) {
                emit(error == null ? "OK:mission.resume" : "ERR:mission.resume: " + error.getDescription());
            }
        });
    }

    private void stop() {
        WaypointMissionOperator op = operator();
        if (op == null) {
            emit("ERR:mission: waypoint operator unavailable");
            return;
        }
        op.stopMission(new CommonCallbacks.CompletionCallback<DJIError>() {
            @Override
            public void onResult(DJIError error) {
                emit(error == null ? "OK:mission.stop" : "ERR:mission.stop: " + error.getDescription());
            }
        });
    }

    private void clear() {
        WaypointMissionOperator op = operator();
        if (op == null) {
            emit("ERR:mission: waypoint operator unavailable");
            return;
        }
        try {
            op.clearMission();
            lastUploaded = false;
            lastIdx = 0;
            emit("OK:mission.clear");
            emit("MISSION:state=" + name(op.getCurrentState()) + ";phase=clear;done=true");
        } catch (Exception e) {
            emit("ERR:mission.clear: " + e.getMessage());
        }
    }

    // Take off, poll until airborne, then start the (already uploaded) mission.
    private void takeoffThenStart() {
        final FlightController fc = flightController();
        if (fc == null) {
            emit("ERR:mission: no flight controller");
            return;
        }
        if (fc.getState() != null && fc.getState().isFlying()) {
            start();
            return;
        }
        emit("[mission] taking off, will start when airborne...");
        fc.startTakeoff(new CommonCallbacks.CompletionCallback<DJIError>() {
            @Override
            public void onResult(DJIError error) {
                if (error != null) {
                    emit("ERR:mission.takeoff: " + error.getDescription());
                    return;
                }
                emit("OK:mission.takeoff");
                waitForAirborne(0);
            }
        });
    }

    private void waitForAirborne(final int attempt) {
        if (attempt > 60) { // ~30 s
            emit("ERR:mission.takeoff: timed out waiting to become airborne");
            return;
        }
        exec.schedule(new Runnable() {
            @Override
            public void run() {
                FlightController fc = flightController();
                if (fc != null && fc.getState() != null && fc.getState().isFlying()) {
                    start();
                } else {
                    waitForAirborne(attempt + 1);
                }
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    private String stateLine() {
        WaypointMissionOperator op = operator();
        if (op == null) {
            return "ERR:mission: waypoint operator unavailable";
        }
        return "MISSION:state=" + name(op.getCurrentState())
                + ";phase=state;idx=" + lastIdx
                + ";total=" + lastTotal
                + ";reached=" + lastReached
                + ";uploaded=" + lastUploaded
                + ";done=false";
    }

    // ------------------------------------------------------------------
    // JSON -> WaypointMission
    // ------------------------------------------------------------------

    private WaypointMission buildMission(String b64) throws Exception {
        String json = new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8");
        JSONObject o = new JSONObject(json);

        float autoSpeed = clamp((float) o.optDouble("autoFlightSpeed", 8.0));
        float maxSpeed = clamp((float) o.optDouble("maxFlightSpeed", 10.0));
        if (maxSpeed < autoSpeed) {
            maxSpeed = autoSpeed;
        }

        WaypointMission.Builder b = new WaypointMission.Builder()
                .autoFlightSpeed(autoSpeed)
                .maxFlightSpeed(maxSpeed)
                .finishedAction(finishedAction(o.optString("finishedAction", "go_home")))
                .headingMode(headingMode(o.optString("headingMode", "auto")))
                .flightPathMode("curved".equalsIgnoreCase(o.optString("flightPathMode", "normal"))
                        ? WaypointMissionFlightPathMode.CURVED
                        : WaypointMissionFlightPathMode.NORMAL)
                .repeatTimes(Math.max(1, o.optInt("repeatTimes", 1)))
                .setExitMissionOnRCSignalLostEnabled(true);

        JSONArray arr = o.getJSONArray("waypoints");
        List<Waypoint> wps = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.getJSONObject(i);
            double lat = w.getDouble("lat");
            double lon = w.getDouble("lon");
            float alt = (float) w.optDouble("alt", 40);
            Waypoint wp = new Waypoint(lat, lon, alt);
            String act = w.optString("action", "none");
            if ("photo".equalsIgnoreCase(act)
                    || "start_take_photo".equalsIgnoreCase(act)) {
                wp.addAction(new WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0));
            } else if ("record".equalsIgnoreCase(act) || "start_record".equalsIgnoreCase(act)) {
                wp.addAction(new WaypointAction(WaypointActionType.START_RECORD, 0));
            } else if ("stop_record".equalsIgnoreCase(act)) {
                wp.addAction(new WaypointAction(WaypointActionType.STOP_RECORD, 0));
            }
            wps.add(wp);
        }
        if (wps.size() < 2) {
            throw new Exception("need at least 2 waypoints (got " + wps.size() + ")");
        }
        b.waypointList(wps).waypointCount(wps.size());
        return b.build();
    }

    private static WaypointMissionFinishedAction finishedAction(String s) {
        switch (s.toLowerCase()) {
            case "no_action":
            case "hover":
            case "none":
                return WaypointMissionFinishedAction.NO_ACTION;
            case "auto_land":
            case "land":
                return WaypointMissionFinishedAction.AUTO_LAND;
            case "go_first":
            case "go_first_waypoint":
                return WaypointMissionFinishedAction.GO_FIRST_WAYPOINT;
            case "continue":
            case "continue_until_end":
                return WaypointMissionFinishedAction.CONTINUE_UNTIL_END;
            case "go_home":
            case "return_home":
            default:
                return WaypointMissionFinishedAction.GO_HOME;
        }
    }

    private static WaypointMissionHeadingMode headingMode(String s) {
        switch (s.toLowerCase()) {
            case "initial":
            case "using_initial_direction":
                return WaypointMissionHeadingMode.USING_INITIAL_DIRECTION;
            case "rc":
            case "control_by_remote_controller":
                return WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER;
            case "waypoint":
            case "using_waypoint_heading":
                return WaypointMissionHeadingMode.USING_WAYPOINT_HEADING;
            case "poi":
            case "toward_point_of_interest":
                return WaypointMissionHeadingMode.TOWARD_POINT_OF_INTEREST;
            case "auto":
            default:
                return WaypointMissionHeadingMode.AUTO;
        }
    }

    private static float clamp(float v) {
        if (v < MIN_SPEED) {
            return MIN_SPEED;
        }
        if (v > MAX_SPEED) {
            return MAX_SPEED;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Operator / listener plumbing
    // ------------------------------------------------------------------

    private WaypointMissionOperator operator() {
        if (operator == null) {
            try {
                DJISDKManager mgr = DJISDKManager.getInstance();
                if (mgr != null && mgr.getMissionControl() != null) {
                    operator = mgr.getMissionControl().getWaypointMissionOperator();
                }
            } catch (Exception e) {
                Log.e(TAG, "get operator", e);
            }
        }
        if (operator != null && !listenerRegistered) {
            operator.addListener(listener);
            listenerRegistered = true;
        }
        return operator;
    }

    private FlightController flightController() {
        BaseProduct product = M2ildaApp.getProductInstance();
        if (product != null && product.isConnected() && product instanceof Aircraft) {
            return ((Aircraft) product).getFlightController();
        }
        return null;
    }

    private static String name(WaypointMissionState state) {
        return state == null ? "UNKNOWN" : state.getName();
    }

    private void emit(String line) {
        // logLine() also writes to logcat, so don't duplicate it here.
        CommandCenter.getInstance().logLine(line);
        BridgeServer.getInstance().broadcastLine(line);
    }

    private final WaypointMissionOperatorListener listener = new WaypointMissionOperatorListener() {
        @Override
        public void onDownloadUpdate(WaypointMissionDownloadEvent event) {
            if (event == null || event.getProgress() == null) {
                return;
            }
            emit("MISSION:state=" + name(operator == null ? null : operator.getCurrentState())
                    + ";phase=download;idx=" + event.getProgress().downloadedWaypointIndex
                    + ";total=" + event.getProgress().totalWaypointCount
                    + ";done=" + event.getProgress().isSummaryDownloaded);
        }

        @Override
        public void onUploadUpdate(WaypointMissionUploadEvent event) {
            if (event == null) {
                return;
            }
            WaypointUploadProgress p = event.getProgress();
            if (p != null) {
                lastTotal = p.totalWaypointCount;
            }
            boolean done = event.getCurrentState() == WaypointMissionState.READY_TO_EXECUTE;
            if (p != null && p.isSummaryUploaded) {
                lastUploaded = true;
            }
            emit("MISSION:state=" + name(event.getCurrentState())
                    + ";phase=upload;idx=" + (p == null ? 0 : p.uploadedWaypointIndex)
                    + ";total=" + (p == null ? lastTotal : p.totalWaypointCount)
                    + ";done=" + done);
        }

        @Override
        public void onExecutionUpdate(WaypointMissionExecutionEvent event) {
            if (event == null) {
                return;
            }
            WaypointExecutionProgress p = event.getProgress();
            if (p != null) {
                lastIdx = p.targetWaypointIndex;
                lastTotal = p.totalWaypointCount;
                lastReached = p.isWaypointReached;
            }
            emit("MISSION:state=" + name(event.getCurrentState())
                    + ";phase=exec;idx=" + lastIdx
                    + ";total=" + lastTotal
                    + ";reached=" + lastReached
                    + ";done=false");
        }

        @Override
        public void onExecutionStart() {
            WaypointMissionState s = operator == null ? null : operator.getCurrentState();
            emit("MISSION:state=" + (s == null ? "EXECUTING" : name(s))
                    + ";phase=exec;event=start;done=false");
        }

        @Override
        public void onExecutionFinish(DJIError error) {
            WaypointMissionState s = operator == null ? null : operator.getCurrentState();
            emit("MISSION:state=" + (s == null ? "READY_TO_EXECUTE" : name(s))
                    + ";phase=exec;event=finish;done=true;"
                    + (error == null ? "ok=1" : "err=" + error.getDescription()));
        }
    };
}
