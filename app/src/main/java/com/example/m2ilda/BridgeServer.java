package com.example.m2ilda;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * TCP bridge that lets the PC ground station (webui.py) talk to the app.
 *   - Command channel on port {@link #COMMAND_PORT}: newline-terminated commands,
 *     replies are "OK:..." / "ERR:..." lines, telemetry is a "T:..." line.
 *   - Video channel on port {@link #VIDEO_PORT}: raw H.264 video data relayed to
 *     every connected client (the PC plays it with jmuxer).
 */
public class BridgeServer {

    private static final String TAG = "BridgeServer";
    public static final int COMMAND_PORT = 5555;
    public static final int VIDEO_PORT = 5556;

    private static BridgeServer instance;

    private final Set<Client> commandClients = new CopyOnWriteArraySet<>();
    private final Set<Socket> videoClients = new HashSet<>();
    private final Object videoLock = new Object();

    private ServerSocket cmdServer;
    private ServerSocket videoServer;
    private Thread cmdThread;
    private Thread videoThread;
    private volatile boolean running;

    private CommandCenter commandCenter;

    static synchronized BridgeServer getInstance() {
        if (instance == null) {
            instance = new BridgeServer();
        }
        return instance;
    }

    private ServerSocket newServer(int port) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        return ss;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        try {
            cmdServer = newServer(COMMAND_PORT);
            Log.i(TAG, "Command bridge listening on :" + COMMAND_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Cannot bind command port " + COMMAND_PORT, e);
            running = false;
            return;
        }
        try {
            videoServer = newServer(VIDEO_PORT);
            Log.i(TAG, "Video relay listening on :" + VIDEO_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Cannot bind video port " + VIDEO_PORT, e);
            closeQuietly(cmdServer);
            running = false;
            return;
        }

        commandCenter = CommandCenter.getInstance();

        cmdThread = new Thread(this::acceptCommands, "bridge-cmd-accept");
        cmdThread.setDaemon(true);
        cmdThread.start();

        videoThread = new Thread(this::acceptVideo, "bridge-video-accept");
        videoThread.setDaemon(true);
        videoThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(cmdServer);
        closeQuietly(videoServer);
        for (Client c : commandClients) {
            c.close();
        }
        commandClients.clear();
        synchronized (videoLock) {
            for (Socket s : videoClients) {
                closeQuietly(s);
            }
            videoClients.clear();
        }
    }

    private void acceptCommands() {
        while (running) {
            try {
                Socket s = cmdServer.accept();
                Client c = new Client(s);
                commandClients.add(c);
                new Thread(c, "bridge-cmd-" + commandClients.size()).start();
                Log.i(TAG, "Command client connected: " + s.getInetAddress());
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Command accept error", e);
                }
            }
        }
    }

    private void acceptVideo() {
        while (running) {
            try {
                Socket s = videoServer.accept();
                synchronized (videoLock) {
                    videoClients.add(s);
                }
                Log.i(TAG, "Video client connected: " + s.getInetAddress());
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Video accept error", e);
                }
            }
        }
    }

    /** Broadcast a telemetry/event line to every command client. */
    public void broadcastLine(String line) {
        for (Client c : commandClients) {
            c.sendLine(line);
        }
    }

    /** Forward raw H.264 video data to every video client. */
    public void broadcastVideo(byte[] data, int size) {
        if (size <= 0) {
            return;
        }
        synchronized (videoLock) {
            if (videoClients.isEmpty()) {
                return;
            }
            for (Socket s : videoClients) {
                try {
                    OutputStream os = s.getOutputStream();
                    os.write(data, 0, size);
                    os.flush();
                } catch (IOException e) {
                    closeQuietly(s);
                    videoClients.remove(s);
                }
            }
        }
    }

    private static void closeQuietly(ServerSocket ss) {
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** One command connection: directive read loop + reply writer. */
    private class Client implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final OutputStream out;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = socket.getOutputStream();
        }

        synchronized void sendLine(String line) {
            try {
                out.write((line + "\n").getBytes("UTF-8"));
                out.flush();
            } catch (IOException e) {
                Log.w(TAG, "Reply write failed", e);
            }
        }

        void close() {
            closeQuietly(socket);
        }

        @Override
        public void run() {
            String line;
            try {
                while (running && (line = in.readLine()) != null) {
                    String cmd = line.trim();
                    if (cmd.isEmpty()) {
                        continue;
                    }
                    String reply = commandCenter.execute(cmd);
                    if (reply != null) {
                        sendLine(reply);
                    }
                }
            } catch (IOException e) {
                // client dropped
            } finally {
                close();
                commandClients.remove(this);
            }
        }
    }
}