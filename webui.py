#!/usr/bin/env python3
"""
M2ilda - Web Ground Control for DJI Mavic 2 Enterprise.

Connects to the phone's bridge (commands on 5555) and live-video stream
(FPV H.264 on 5556), then serves a browser dashboard that can:
  - send every flight/camera command
  - show live telemetry
  - play the drone's live camera feed

Run it and a browser tab opens automatically:
    python3 webui.py

The phone must be plugged in over USB (auto-detected) - or pass --host.
"""

import argparse
import base64
import hashlib
import http.server
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import webbrowser

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ground_station as gs

DEFAULT_HTTP_PORT = 8080
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# ---------------------------------------------------------------------------
# Global state
# ---------------------------------------------------------------------------
run = True

CMD_CLIENTS = set()      # websocket sockets for /ws/cmd
VID_CLIENTS = set()      # websocket sockets for /ws/video
CLIENT_LOCKS = {}        # socket -> lock guarding writes to that socket
GLOBAL_PRINT_LOCK = threading.Lock()

phone_host = None
phone_how = None

cmd_phone = None      # socket to phone bridge :5555
cmd_phone_lock = threading.Lock()
cmd_phone_writer = None

vid_phone = None      # socket to phone video :5556

last_status = {}
last_video_time = 0.0

adb_state = {"connected": False, "serial": None}
stream_state = {"live": False}


def stream_poller():
    import urllib.request
    while run:
        try:
            with urllib.request.urlopen(
                    "http://localhost:8888/drone/index.m3u8", timeout=4) as r:
                stream_state["live"] = r.status in (200, 302)
        except Exception:
            stream_state["live"] = False
        time.sleep(3)


def adb_poller():
    while run:
        try:
            out = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True,
                timeout=5).stdout
            serial = None
            for line in out.splitlines()[1:]:
                parts = line.split()
                if len(parts) == 2 and parts[1] == "device":
                    serial = parts[0]
                    break
            adb_state["connected"] = serial is not None
            adb_state["serial"] = serial
        except (OSError, subprocess.TimeoutExpired):
            adb_state["connected"] = False
            adb_state["serial"] = None
        time.sleep(3)


def log(msg):
    with GLOBAL_PRINT_LOCK:
        print("[webui] %s" % msg, flush=True)


# ---------------------------------------------------------------------------
# Phone bridge connections
# ---------------------------------------------------------------------------

def cmd_phone_loop():
    global cmd_phone
    while run:
        try:
            s = socket.create_connection((phone_host, gs.DEFAULT_PORT), timeout=6)
            s.settimeout(None)
            with cmd_phone_lock:
                cmd_phone = s
            log("Phone command bridge connected (%s)." % phone_host)
            buf = b""
            while run:
                try:
                    chunk = s.recv(4096)
                except OSError:
                    break
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, _, buf = buf.partition(b"\n")
                    text = line.decode(errors="replace").strip()
                    if text:
                        bcast_cmd_text(text)
        except (OSError, socket.timeout) as e:
            if run:
                log("Command bridge error: %s - retrying..." % e)
        with cmd_phone_lock:
            cmd_phone = None
        time.sleep(3)
    log("Command bridge stopped.")


def vid_phone_loop():
    global vid_phone, last_video_time
    while run:
        try:
            s = socket.create_connection((phone_host, gs.VIDEO_PORT), timeout=6)
            s.settimeout(None)
            vid_phone = s
            log("Phone video stream connected.")
            while run:
                try:
                    chunk = s.recv(65536)
                except OSError:
                    break
                if not chunk:
                    break
                last_video_time = time.time()
                bcast_video(chunk)
        except (OSError, socket.timeout) as e:
            if run:
                log("Video stream error: %s - retrying..." % e)
        vid_phone = None
        time.sleep(3)
    log("Video stream stopped.")


def telemetry_poller():
    while run:
        time.sleep(2.5)
        send_phone_cmd("status")


def send_phone_cmd(cmd):
    # Do NOT lowercase: mission payloads carry case-sensitive base64. The app
    # lowercases ordinary commands itself before dispatching them.
    line = (cmd.strip() + "\n").encode()
    with cmd_phone_lock:
        s = cmd_phone
        if s is None:
            return False
        try:
            s.sendall(line)
            return True
        except OSError:
            return False


# ---------------------------------------------------------------------------
# WebSocket helpers (RFC 6455, minimal)
# ---------------------------------------------------------------------------

def ws_handshake_response(request):
    key = request.headers.get("Sec-WebSocket-Key")
    if not key:
        return False
    accept = base64.b64encode(
        hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
    body = ("HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
    request.wfile.write(body.encode())
    request.wfile.flush()
    return True


def recv_exact(f, n):
    data = b""
    while len(data) < n:
        chunk = f.read(n - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def ws_read_frame(f):
    hdr = f.read(2)
    if not hdr or len(hdr) < 2:
        return None, None
    b1, b2 = hdr
    opcode = b1 & 0x0F
    ln = b2 & 0x7F
    masked = b2 & 0x80
    if ln == 126:
        raw = recv_exact(f, 2)
        if raw is None:
            return None, None
        ln = struct.unpack(">H", raw)[0]
    elif ln == 127:
        raw = recv_exact(f, 8)
        if raw is None:
            return None, None
        ln = struct.unpack(">Q", raw)[0]
    mask = recv_exact(f, 4) if ln and masked else b""
    payload = recv_exact(f, ln)
    if payload is None:
        return None, None
    if masked:
        payload = bytes(c ^ mask[i % 4] for i, c in enumerate(payload))
    return opcode, payload


def ws_send(conn, opcode, payload, lock):
    with lock:
        header = struct.pack("!B", 0x80 | opcode)
        n = len(payload)
        if n < 126:
            header += struct.pack("!B", n)
        elif n < 65536:
            header += struct.pack("!BH", 126, n)
        else:
            header += struct.pack("!BQ", 127, n)
        conn.sendall(header + payload)


def drop_client(sock, clients):
    if sock in clients:
        clients.discard(sock)
    CLIENT_LOCKS.pop(sock, None)
    try:
        sock.close()
    except OSError:
        pass


def bcast(clients, opcode, payload):
    for sock in list(clients):
        lock = CLIENT_LOCKS.get(sock)
        if lock is None:
            continue
        try:
            ws_send(sock, opcode, payload, lock)
        except OSError:
            drop_client(sock, clients)


def bcast_cmd_text(text):
    log("phone< " + text)
    bcast(CMD_CLIENTS, 0x1, text.encode())


def bcast_video(data):
    bcast(VID_CLIENTS, 0x2, data)


def serve_ws(request, kind):
    if not ws_handshake_response(request):
        return
    sock = request.connection
    clients = CMD_CLIENTS if kind == "cmd" else VID_CLIENTS
    clients.add(sock)
    CLIENT_LOCKS[sock] = threading.Lock()
    f = sock.makefile("rb")
    try:
        while run:
            opcode, payload = ws_read_frame(f)
            if opcode is None:
                break
            if opcode == 0x8:      # close
                try:
                    ws_send(sock, 0x8, b"", CLIENT_LOCKS[sock])
                except OSError:
                    pass
                break
            elif opcode == 0x9:    # ping -> pong
                try:
                    ws_send(sock, 0xA, payload, CLIENT_LOCKS[sock])
                except OSError:
                    break
            elif opcode == 0x1 and kind == "cmd":   # command text
                text = payload.decode(errors="replace").strip()
                if text:
                    log("browser> " + text)
                    send_phone_cmd(text)
    except (OSError, ConnectionError):
        pass
    finally:
        try:
            f.close()
        except OSError:
            pass
        drop_client(sock, clients)


# ---------------------------------------------------------------------------
# HTTP server
# ---------------------------------------------------------------------------

HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>M2ilda · Ground Control</title>
<style>
  *{box-sizing:border-box; margin:0; padding:0;}
  body{background:#000; color:#fff; height:100vh;
       font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
       display:flex; flex-direction:column; overflow:hidden;
       -webkit-font-smoothing:antialiased;}
  header{background:#000; border-bottom:1px solid #1e1e1e;
         padding:14px 20px; display:flex; align-items:center; gap:22px;}
  header h1{font-size:15px; letter-spacing:5px; font-weight:600; color:#fff;
            white-space:nowrap;}
  header h1::after{content:""; display:inline-block; width:5px; height:5px;
                   border-radius:50%; background:#fff; margin-left:12px; vertical-align:2px;}
  header .badge{display:flex; align-items:center; gap:9px; font-size:10px;
                letter-spacing:2px; color:#555; text-transform:uppercase;}
  header .badge b{width:7px; height:7px; border-radius:50%; background:#333;}
  header .badge.on{color:#fff;} header .badge.on b{background:#fff;}
  header .badge.off{color:#555;} header .badge.off b{background:#333;}
  header .hint{margin-left:auto; font-size:10px; letter-spacing:1px; color:#444;
               text-transform:uppercase;}
  main{flex:1; display:flex; min-height:0;}
  #left{flex:1; display:flex; flex-direction:column; gap:12px;
        padding:14px; min-width:0;}
  #videoWrap{flex:1; background:#000; border:1px solid #1e1e1e;
             position:relative; min-height:0;}
  #stream{width:100%; height:100%; border:0; display:block; background:#000;}
  #telemetry{display:flex; gap:8px; flex-wrap:wrap;}
  .tile{flex:1; min-width:94px; padding:10px 14px; background:#070707;
        border:1px solid #1e1e1e;}
  .tile .k{font-size:9px; letter-spacing:2px; color:#666; text-transform:uppercase;}
  .tile .v{font-size:21px; font-weight:600; color:#fff; margin-top:3px;
           font-variant-numeric:tabular-nums;}
  .tile.alert .v{color:#E5484D;}
  #right{width:322px; border-left:1px solid #1e1e1e; background:#060606;
         display:flex; flex-direction:column; padding:12px;}
  #right h2{font-size:10px; letter-spacing:3px; color:#8a8a8a; text-transform:uppercase;
            padding:8px 0; border-bottom:1px solid #1e1e1e; margin-bottom:10px;}
  .dpad{display:grid; grid-template-columns:repeat(3,1fr); gap:6px; margin-bottom:10px;}
  .dpad .btn{padding:12px 0; font-size:12px;}
  .grid{display:grid; grid-template-columns:repeat(2,1fr); gap:6px;}
  .btn{background:#141414; border:1px solid #2a2a2a; color:#fff; cursor:pointer;
       font-family:inherit; font-size:13px; font-weight:600; letter-spacing:1.5px;
       text-transform:uppercase; padding:15px 0; transition:background .12s,color .12s,border-color .12s;}
  .btn:hover{background:#fff; color:#000; border-color:#fff;}
  .btn:active{transform:scale(.97);}
  .btn.white{background:#fff; color:#000; border-color:#fff; font-weight:700;}
  .btn.white:hover{background:#e8e8e8;}
  .btn.red{background:#E5484D; color:#fff; border-color:#E5484D; font-weight:700;}
  .btn.red:hover{background:#fff; color:#E5484D; border-color:#fff;}
  .btn.dim{background:transparent; color:#fff; border:1px solid #2a2a2a;}
  .btn.dim:hover{background:#fff; color:#000;}
  .btn span{display:block; font-size:8px; letter-spacing:1.5px; color:#666; font-weight:400;}
  .btn.white span{color:#000;} .btn.red span{color:rgba(255,255,255,.8);}
  .btn.dim span{color:#666;}
  .btn.wide{grid-column:span 2;}
  #log{flex:1; min-height:60px; margin-top:12px; background:#000;
       border:1px solid #1e1e1e; overflow-y:auto; padding:10px;
       font-family:ui-monospace,SFMono-Regular,Consolas,Menlo,monospace;
       font-size:11px; color:#d5d5d5; line-height:1.5;}
  #log div{border-bottom:1px dashed #161616; padding:3px 0;}
  #log .in{color:#fff;} #log .out{color:#888;} #log .sys{color:#555;}
  #log .err{color:#E5484D;}
</style>
</head>
<body>
<header>
  <h1>MAVIC 2 ENTERPRISE</h1>
  <span class="badge" id="badgePhone"><b></b>PHONE</span>
  <span class="badge" id="badgeVideo"><b></b>VIDEO</span>
  <span class="badge" id="badgeBrowser"><b></b>BROWSER</span>
  <span class="badge" id="badgeAdb"><b></b>ADB</span>
  <span class="hint" id="hint"></span>
</header>
<main>
  <section id="left">
    <div id="videoWrap">
      <iframe id="stream" src="http://localhost:8888/drone/"
              allow="autoplay; fullscreen" allowfullscreen></iframe>
    </div>
    <div id="telemetry">
      <div class="tile"><div class="k">ALT (m)</div><div class="v" id="t-alt">--</div></div>
      <div class="tile"><div class="k">SATS</div><div class="v" id="t-sats">--</div></div>
      <div class="tile"><div class="k">SPEED (m/s)</div><div class="v" id="t-speed">--</div></div>
      <div class="tile"><div class="k">YAW (°)</div><div class="v" id="t-yaw">--</div></div>
      <div class="tile"><div class="k">PITCH (°)</div><div class="v" id="t-pitch">--</div></div>
      <div class="tile"><div class="k">ROLL (°)</div><div class="v" id="t-roll">--</div></div>
      <div class="tile"><div class="k">FLYING</div><div class="v" id="t-flying">--</div></div>
      <div class="tile"><div class="k">DRONE</div><div class="v" id="t-drone">--</div></div>
    </div>
  </section>

  <section id="right">
    <button class="btn wide" style="width:100%;margin-bottom:12px"
            onclick="window.open('/waypoints','_blank')">
      WAYPOINT PLANNER<span>open in new tab</span>
    </button>
    <h2>FLIGHT COMMANDS</h2>
    <div class="dpad">
      <div style="visibility:hidden"><button class="btn">·</button></div>
      <button class="btn" onclick="send('fwd')">FWD</button>
      <div style="visibility:hidden"><button class="btn">·</button></div>
      <button class="btn" onclick="send('left')">LEFT</button>
      <button class="btn" onclick="send('back')">BACK</button>
      <button class="btn" onclick="send('right')">RIGHT</button>
    </div>
    <div class="grid" style="margin-bottom:10px">
      <button class="btn dim" onclick="send('up')">CLIMB<span>+ alt</span></button>
      <button class="btn dim" onclick="send('down')">DESCEND<span>- alt</span></button>
      <button class="btn dim" onclick="send('rotl')">ROTATE L</button>
      <button class="btn dim" onclick="send('rotr')">ROTATE R</button>
      <button class="btn white wide" onclick="send('takeoff')">TAKEOFF<span>auto takeoff</span></button>
      <button class="btn red wide" onclick="send('land')">LAND<span>auto landing</span></button>
      <button class="btn dim wide" onclick="send('photo')">PHOTO<span>capture</span></button>
      <button class="btn" onclick="send('rec')">REC</button>
      <button class="btn dim" onclick="send('stop')">STOP REC</button>
    </div>
    <h2 style="margin-top:12px">EVENT LOG</h2>
    <div id="log"></div>
  </section>
</main>

<script>
const logBox = document.getElementById('log');
let cmdSocket = null;

function setBadge(id, on){
  const el = document.getElementById(id);
  el.classList.toggle('on', on === true);
  el.classList.toggle('off', on === false);
}
function log(msg, cls){
  const d = document.createElement('div');
  d.className = cls || 'sys';
  d.textContent = new Date().toLocaleTimeString() + '  ' + msg;
  logBox.appendChild(d);
  while(logBox.children.length > 400) logBox.removeChild(logBox.firstChild);
  logBox.scrollTop = logBox.scrollHeight;
}
function parseStatus(text){
  text.replace(/^T:/,'').split(';').forEach(pair=>{
    const eq = pair.indexOf('=');
    if(eq < 0) return;
    const k = pair.slice(0,eq), v = pair.slice(eq+1);
    const map = {alt:'t-alt', sats:'t-sats', speed:'t-speed', yaw:'t-yaw',
                 pitch:'t-pitch', roll:'t-roll', flying:'t-flying',
                 connected:'t-drone'};
    if(map[k]){
      const el = document.getElementById(map[k]);
      el.textContent =
        k==='connected' ? (v==='true'?'ONLINE':'OFFLINE') :
        k==='flying' ? (v==='true'?'YES':'NO') : v;
      if(k==='connected') el.parentElement.classList.toggle('alert', v!=='true');
    }
  });
}
function send(cmd){
  if(cmdSocket && cmdSocket.readyState === 1){
    cmdSocket.send(cmd);
    log('> ' + cmd.toUpperCase(), 'out');
  } else {
    log('command socket not connected', 'err');
  }
}
function connectCmd(){
  cmdSocket = new WebSocket((location.protocol==='https:'?'wss://':'ws://') + location.host + '/ws/cmd');
  cmdSocket.onopen = ()=>{ setBadge('badgeBrowser', true); log('browser link up', 'sys'); };
  cmdSocket.onclose = ()=>{ setBadge('badgeBrowser', false); setTimeout(connectCmd, 2000); };
  cmdSocket.onerror = ()=>{ cmdSocket.close(); };
  cmdSocket.onmessage = (e)=>{
    const msg = e.data;
    // Telemetry + poller acks are not events; just update the dashboards.
    if(typeof msg === 'string' && (msg.startsWith('T:') || msg === 'OK:status')){
      if(msg.startsWith('T:')) parseStatus(msg);
      return;
    }
    log('<- ' + msg, 'in');
  };
}
function pollAdb(){
  fetch('/api/adb').then(r=>r.json()).then(d=>{
    setBadge('badgeAdb', d.connected === true);
    document.getElementById('hint').textContent =
      d.connected ? ('ADB ' + d.serial) : 'ADB OFFLINE';
  }).catch(()=>setBadge('badgeAdb', false));
  fetch('/api/stream').then(r=>r.json()).then(d=>{
    setBadge('badgeVideo', d.live === true);
  }).catch(()=>setBadge('badgeVideo', false));
}
window.addEventListener('load', ()=>{
  connectCmd();
  setInterval(pollAdb, 3000); pollAdb();
});
</script>
</body>
</html>"""


WAYPOINT_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>M2ilda · Waypoint Planner</title>
<link rel="stylesheet" href="/leaflet.css">
<style>
  *{box-sizing:border-box; margin:0; padding:0;}
  html,body{height:100%;}
  body{background:#000; color:#fff; height:100vh; overflow:hidden;
       font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
       display:flex; flex-direction:column; -webkit-font-smoothing:antialiased;}
  header{background:#000; border-bottom:1px solid #1e1e1e; padding:10px 16px;
         display:flex; align-items:center; gap:18px; flex-wrap:wrap;}
  header h1{font-size:13px; letter-spacing:4px; font-weight:600; white-space:nowrap;}
  header h1::after{content:""; display:inline-block; width:5px; height:5px; border-radius:50%;
                   background:#fff; margin-left:10px; vertical-align:2px;}
  .badge{display:flex; align-items:center; gap:7px; font-size:10px; letter-spacing:2px;
         color:#555; text-transform:uppercase;}
  .badge b{width:7px; height:7px; border-radius:50%; background:#333;}
  .badge.on{color:#fff;} .badge.on b{background:#fff;}
  .badge.warn{color:#E5A94D;} .badge.warn b{background:#E5A94D;}
  .badge.err{color:#E5484D;} .badge.err b{background:#E5484D;}
  header .spacer{margin-left:auto;}
  header a.back{color:#888; font-size:10px; letter-spacing:2px; text-decoration:none;
                text-transform:uppercase; border:1px solid #2a2a2a; padding:6px 10px;}
  header a.back:hover{color:#000; background:#fff; border-color:#fff;}
  main{flex:1; display:flex; min-height:0;}
  #map{flex:1; min-width:0; background:#111;}
  #panel{width:390px; border-left:1px solid #1e1e1e; background:#060606; overflow-y:auto;
         padding:12px; display:flex; flex-direction:column; gap:12px;}
  #panel h2{font-size:10px; letter-spacing:3px; color:#8a8a8a; text-transform:uppercase;
            padding:6px 0; border-bottom:1px solid #1e1e1e;}
  label{display:flex; align-items:center; justify-content:space-between; gap:10px;
        font-size:11px; color:#8a8a8a; letter-spacing:1px; text-transform:uppercase;
        margin:7px 0;}
  input,select{background:#101010; border:1px solid #2a2a2a; color:#fff; font-family:inherit;
               font-size:12px; padding:6px 8px; width:130px;}
  input:focus,select:focus{outline:none; border-color:#666;}
  .btn{background:#141414; border:1px solid #2a2a2a; color:#fff; cursor:pointer;
       font-family:inherit; font-size:11px; font-weight:600; letter-spacing:1.5px;
       text-transform:uppercase; padding:11px 0; transition:background .12s,color .12s,border-color .12s;}
  .btn:hover{background:#fff; color:#000; border-color:#fff;}
  .btn:active{transform:scale(.98);}
  .btn:disabled{opacity:.35; cursor:not-allowed;}
  .btn.white{background:#fff; color:#000; border-color:#fff; font-weight:700;}
  .btn.red{background:#E5484D; color:#fff; border-color:#E5484D; font-weight:700;}
  .btn.red:hover{background:#fff; color:#E5484D;}
  .row2{display:grid; grid-template-columns:1fr 1fr; gap:6px;}
  .row3{display:grid; grid-template-columns:1fr 1fr 1fr; gap:6px;}
  .row4{display:grid; grid-template-columns:repeat(4,1fr); gap:6px;}
  #wpList{display:flex; flex-direction:column; gap:5px; max-height:260px; overflow-y:auto;}
  .wp-row{display:grid; grid-template-columns:24px 1fr 92px 28px; gap:6px; align-items:center;
          background:#0b0b0b; border:1px solid #1b1b1b; padding:5px 6px;}
  .wp-n{font-size:11px; color:#fff; font-weight:700; text-align:center;
        background:#1c1c1c; border-radius:50%; width:22px; height:22px; line-height:22px;}
  .wp-coord{font-size:10px; color:#777; font-family:ui-monospace,Menlo,Consolas,monospace;
            white-space:nowrap; overflow:hidden; text-overflow:ellipsis;}
  .wp-row input{width:100%; padding:4px 6px; font-size:11px;}
  .wp-row select{width:100%; padding:4px 6px; font-size:11px;}
  .wp-del{background:transparent; border:1px solid #2a2a2a; color:#E5484D; cursor:pointer;
          font-size:12px; line-height:1; padding:5px 0;}
  .wp-del:hover{background:#E5484D; color:#fff; border-color:#E5484D;}
  #progressWrap{background:#101010; border:1px solid #2a2a2a; height:18px; position:relative;}
  #progressBar{height:100%; width:0%; background:#fff; transition:width .25s;}
  #progressWrap span{position:absolute; inset:0; text-align:center; font-size:10px;
                     letter-spacing:1px; line-height:16px; color:#000; mix-blend-mode:difference;}
  #log{background:#000; border:1px solid #1e1e1e; overflow-y:auto; padding:8px; min-height:80px;
       max-height:180px; font-family:ui-monospace,Menlo,Consolas,monospace; font-size:11px;
       color:#d5d5d5; line-height:1.45;}
  #log div{border-bottom:1px dashed #161616; padding:2px 0; word-break:break-word;}
  #log .in{color:#fff;} #log .out{color:#888;} #log .sys{color:#555;} #log .err{color:#E5484D;}
  .wpIcon{background:none; border:none;}
  .wpIcon .pin{width:26px; height:26px; border-radius:50%; background:#fff; color:#000;
               font-size:12px; font-weight:700; line-height:26px; text-align:center;
               border:2px solid #000; box-shadow:0 0 0 1px #fff;}
  .leaflet-popup-content-wrapper,.leaflet-popup-tip{background:#111; color:#fff;}
  .leaflet-container{font-family:inherit;}
</style>
</head>
<body>
<header>
  <h1>WAYPOINT PLANNER</h1>
  <span class="badge" id="bLink"><b></b>LINK</span>
  <span class="badge" id="bGps"><b></b>GPS</span>
  <span class="badge" id="bFly"><b></b>FLYING</span>
  <span class="badge" id="bState"><b></b><span id="stateText">IDLE</span></span>
  <span class="spacer"></span>
  <a class="back" href="/" target="_blank">← dashboard</a>
</header>
<main>
  <div id="map"></div>
  <aside id="panel">

    <h2>Mission</h2>
    <div id="progressWrap"><div id="progressBar"></div><span id="progressText">no mission</span></div>
    <div class="row2" style="margin-top:8px">
      <button class="btn" id="btnUpload">Upload</button>
      <button class="btn" id="btnRun">Run</button>
    </div>
    <div class="row2" style="margin-top:6px">
      <button class="btn white" id="btnTakeoffRun">Take off &amp; start</button>
      <button class="btn" id="btnPause">Pause</button>
    </div>
    <div class="row3" style="margin-top:6px">
      <button class="btn" id="btnResume">Resume</button>
      <button class="btn red" id="btnStop">Stop</button>
      <button class="btn" id="btnClearMission">Clear</button>
    </div>

    <h2>Flight settings</h2>
    <label>Cruise speed (m/s)<input id="set-auto" type="number" min="2" max="15" step="1" value="8"></label>
    <label>Max speed (m/s)<input id="set-max" type="number" min="2" max="15" step="1" value="10"></label>
    <label>Default altitude (m)<input id="set-defalt" type="number" min="-200" max="500" step="1" value="40"></label>
    <label>Finished action
      <select id="set-finish">
        <option value="go_home" selected>Return home</option>
        <option value="hover">Hover</option>
        <option value="auto_land">Auto land</option>
        <option value="go_first">Return to first</option>
        <option value="continue">Continue</option>
      </select></label>
    <label>Heading
      <select id="set-heading">
        <option value="auto" selected>Auto (direction of flight)</option>
        <option value="initial">Initial direction</option>
        <option value="waypoint">Per-waypoint</option>
        <option value="rc">Remote controller</option>
      </select></label>
    <label>Path
      <select id="set-path">
        <option value="normal" selected>Straight</option>
        <option value="curved">Curved</option>
      </select></label>
    <label>Repeat<input id="set-repeat" type="number" min="1" max="255" step="1" value="1"></label>

    <h2>Waypoints <span id="wpCount" style="color:#fff">(0)</span></h2>
    <div class="row2">
      <button class="btn" id="btnCenter">Locate drone</button>
      <button class="btn" id="btnAddDrone">+ at drone</button>
    </div>
    <p style="font-size:10px;color:#666;letter-spacing:1px;margin-top:6px">
      Click the map to add. Drag a pin to move. An aircraft must have GPS lock.</p>
    <div id="wpList" style="margin-top:8px"></div>
    <div class="row3" style="margin-top:8px">
      <button class="btn" id="btnSave">Save</button>
      <button class="btn" id="btnLoad">Load</button>
      <button class="btn" id="btnClearMap">Clear map</button>
    </div>
    <input id="fileInput" type="file" accept=".json,application/json" style="display:none">

    <h2>Event log</h2>
    <div id="log"></div>
  </aside>
</main>

<script src="/leaflet.js"></script>
<script>
const DEFAULT_CENTER = [22.5362, 113.9454];

const state = {
  waypoints: [],          // {lat, lng, alt, action}
  markers: [],
  line: null,
  drone: null,
  droneMarker: null,
  hasDrone: false,
  ws: null
};

// ---------------------------------------------------------------- map
const map = L.map('map', {zoomControl: true}).setView(DEFAULT_CENTER, 16);
const streets = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
  {maxZoom: 19, attribution: '&copy; OpenStreetMap'});
const satellite = L.tileLayer(
  'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
  {maxZoom: 19, attribution: 'Esri'});
streets.addTo(map);
L.control.layers({'Streets': streets, 'Satellite': satellite}, {}, {position: 'topleft'}).addTo(map);
L.Icon.Default.imagePath = '/leaflet-images/';

map.on('click', (e) => {
  addWaypoint(e.latlng.lat, e.latlng.lng);
});

function wpIcon(n) {
  return L.divIcon({className: 'wpIcon',
    html: '<div class="pin">' + n + '</div>', iconSize: [26, 26], iconAnchor: [13, 13]});
}

function addWaypoint(lat, lng, alt, action) {
  state.waypoints.push({
    lat: lat, lng: lng,
    alt: (alt === undefined ? defaultAlt() : alt),
    action: action || 'none'
  });
  redraw();
}

function defaultAlt() {
  const el = document.getElementById('set-defalt');
  const v = parseFloat(el ? el.value : 40);
  return isFinite(v) ? v : 40;
}

function redraw() {
  state.markers.forEach(m => map.removeLayer(m));
  state.markers = [];
  state.waypoints.forEach((wp, i) => {
    const m = L.marker([wp.lat, wp.lng], {icon: wpIcon(i + 1), draggable: true}).addTo(map);
    m.on('dragend', () => {
      const ll = m.getLatLng();
      wp.lat = ll.lat; wp.lng = ll.lng;
      updateLine();
      updateRowCoords(i);
    });
    state.markers.push(m);
  });
  updateLine();
  renderList();
}

function updateLine() {
  if (state.line) { map.removeLayer(state.line); state.line = null; }
  if (state.waypoints.length >= 2) {
    state.line = L.polyline(
      state.waypoints.map(w => [w.lat, w.lng]), {color: '#fff', weight: 2}).addTo(map);
  }
  document.getElementById('wpCount').textContent = '(' + state.waypoints.length + ')';
}

// ---------------------------------------------------------------- waypoint list
function renderList() {
  const list = document.getElementById('wpList');
  list.innerHTML = '';
  state.waypoints.forEach((wp, i) => {
    const row = document.createElement('div');
    row.className = 'wp-row';
    row.innerHTML =
      '<span class="wp-n">' + (i + 1) + '</span>' +
      '<span class="wp-coord" id="coord-' + i + '">' + fmt(wp.lat) + ', ' + fmt(wp.lng) + '</span>' +
      '<input type="number" min="-200" max="500" step="1" value="' + Math.round(wp.alt) + '" title="altitude (m)">' +
      '<button class="wp-del" title="remove">&times;</button>' +
      '<select style="grid-column:2 / span 3">' +
        opt('none', 'No action', wp.action) +
        opt('photo', 'Take photo', wp.action) +
        opt('record', 'Start recording', wp.action) +
        opt('stop_record', 'Stop recording', wp.action) +
      '</select>';
    const altInput = row.querySelector('input');
    altInput.addEventListener('change', () => {
      wp.alt = Math.max(-200, Math.min(500, parseFloat(altInput.value) || 40));
      altInput.value = Math.round(wp.alt);
    });
    const sel = row.querySelector('select');
    sel.addEventListener('change', () => { wp.action = sel.value; });
    row.querySelector('.wp-del').addEventListener('click', () => {
      state.waypoints.splice(i, 1);
      redraw();
    });
    list.appendChild(row);
  });
}

function updateRowCoords(i) {
  const el = document.getElementById('coord-' + i);
  if (el) el.textContent = fmt(state.waypoints[i].lat) + ', ' + fmt(state.waypoints[i].lng);
}

function opt(v, label, cur) {
  return '<option value="' + v + '"' + (v === cur ? ' selected' : '') + '>' + label + '</option>';
}
function fmt(v) { return (Math.round(v * 1e5) / 1e5).toFixed(5); }

// ---------------------------------------------------------------- websocket
function log(msg, cls) {
  const box = document.getElementById('log');
  const d = document.createElement('div');
  d.className = cls || 'sys';
  d.textContent = new Date().toLocaleTimeString() + '  ' + msg;
  box.appendChild(d);
  while (box.children.length > 300) box.removeChild(box.firstChild);
  box.scrollTop = box.scrollHeight;
}

function connect() {
  state.ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') +
    location.host + '/ws/cmd');
  state.ws.onopen = () => { setBadge('bLink', 'on'); log('link up', 'sys'); send('mission state'); };
  state.ws.onclose = () => { setBadge('bLink', 'err'); setTimeout(connect, 2000); };
  state.ws.onerror = () => { state.ws.close(); };
  state.ws.onmessage = (e) => {
    const m = e.data;
    if (typeof m !== 'string') return;
    if (m.startsWith('T:')) { parseTelemetry(m); return; }
    if (m.startsWith('MISSION:')) { parseMission(m); return; }
    log(m, m.startsWith('ERR') ? 'err' : (m.startsWith('<') ? 'in' : 'in'));
  };
}

function send(cmd) {
  if (state.ws && state.ws.readyState === 1) {
    state.ws.send(cmd);
    const shown = cmd.length > 90
      ? cmd.slice(0, 60) + ' … (' + cmd.length + ' chars)' : cmd;
    log('> ' + shown, 'out');
  } else {
    log('link not connected', 'err');
  }
}

function setBadge(id, cls) {
  const el = document.getElementById(id);
  if (!el) return;
  el.classList.remove('on', 'warn', 'err');
  if (cls) el.classList.add(cls);
}

// ---------------------------------------------------------------- parsing
function kv(text) {
  const o = {};
  text.split(';').forEach(p => {
    const i = p.indexOf('=');
    if (i > 0) o[p.slice(0, i)] = p.slice(i + 1);
  });
  return o;
}

function parseTelemetry(m) {
  const o = kv(m.replace(/^T:/, ''));
  const connected = o.connected === 'true';
  setBadge('bGps', o.sats && parseInt(o.sats) >= 6 ? 'on' : 'warn');
  document.getElementById('bGps').innerHTML = '<b></b>GPS ' + (o.sats || 0);
  setBadge('bFly', o.flying === 'true' ? 'on' : null);
  document.getElementById('bFly').innerHTML = '<b></b>FLYING ' + (o.flying === 'true' ? 'YES' : 'NO');

  const lat = parseFloat(o.lat), lng = parseFloat(o.lon);
  if (isFinite(lat) && isFinite(lng)) {
    state.hasDrone = true;
    state.drone = [lat, lng];
    if (!state.droneMarker) {
      state.droneMarker = L.circleMarker([lat, lng], {
        radius: 6, color: '#E5A94D', fillColor: '#E5A94D', fillOpacity: .9, weight: 2
      }).addTo(map).bindTooltip('Drone');
    } else {
      state.droneMarker.setLatLng([lat, lng]);
    }
  } else {
    state.hasDrone = false;
  }
  setBadge('bLink', connected ? 'on' : 'err');
}

function parseMission(m) {
  const o = kv(m.replace(/^MISSION:/, ''));
  const stateText = o.state || '--';
  document.getElementById('stateText').textContent = stateText;
  const st = document.getElementById('bState');
  st.classList.remove('on', 'warn', 'err');
  if (stateText === 'EXECUTING') st.classList.add('on');
  else if (stateText === 'EXECUTION_PAUSED' || stateText === 'UPLOADING') st.classList.add('warn');
  else if (stateText === 'READY_TO_EXECUTE' || stateText === 'READY_TO_UPLOAD') st.classList.add('on');

  const idx = parseInt(o.idx || '0');
  const total = parseInt(o.total || '0');
  const bar = document.getElementById('progressBar');
  const txt = document.getElementById('progressText');
  if (total > 0) {
    const done = Math.max(0, Math.min(idx, total));
    const pct = stateText === 'EXECUTING' ? (done / total) * 100 : (o.done === 'true' ? 100 : 0);
    bar.style.width = pct + '%';
    txt.textContent = idx + ' / ' + total + '  ·  ' + stateText;
  } else {
    txt.textContent = stateText;
  }
  log('< ' + m, 'in');
}

// ---------------------------------------------------------------- commands
function b64utf8(str) {
  const bytes = new TextEncoder().encode(str);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function collectMission() {
  if (state.waypoints.length < 2) {
    throw new Error('Add at least 2 waypoints first');
  }
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  return {
    autoFlightSpeed: clamp(parseFloat(document.getElementById('set-auto').value) || 8, 2, 15),
    maxFlightSpeed: clamp(parseFloat(document.getElementById('set-max').value) || 10, 2, 15),
    finishedAction: document.getElementById('set-finish').value,
    headingMode: document.getElementById('set-heading').value,
    flightPathMode: document.getElementById('set-path').value,
    repeatTimes: Math.max(1, parseInt(document.getElementById('set-repeat').value) || 1),
    waypoints: state.waypoints.map(w => ({
      lat: +w.lat, lon: +w.lng, alt: +w.alt, action: w.action
    }))
  };
}

function runMission(kind) {
  let mission;
  try {
    mission = collectMission();
  } catch (e) {
    log(e.message, 'err');
    return;
  }
  send(kind + ' ' + b64utf8(JSON.stringify(mission)));
}

// ---------------------------------------------------------------- UI wiring
document.getElementById('btnUpload').onclick = () => runMission('mission upload');
document.getElementById('btnRun').onclick = () => runMission('mission run');
document.getElementById('btnTakeoffRun').onclick = () => runMission('takeoff_run');
document.getElementById('btnPause').onclick = () => send('mission pause');
document.getElementById('btnResume').onclick = () => send('mission resume');
document.getElementById('btnStop').onclick = () => send('mission stop');
document.getElementById('btnClearMission').onclick = () => send('mission clear');
document.getElementById('btnCenter').onclick = () => {
  if (state.hasDrone) map.setView(state.drone, 18);
  else log('no GPS fix yet', 'err');
};
document.getElementById('btnAddDrone').onclick = () => {
  if (state.hasDrone) addWaypoint(state.drone[0], state.drone[1]);
  else log('no GPS fix yet', 'err');
};
document.getElementById('btnClearMap').onclick = () => {
  state.waypoints = [];
  redraw();
};
document.getElementById('btnSave').onclick = () => {
  let mission;
  try { mission = collectMission(); } catch (e) { log(e.message, 'err'); return; }
  const blob = new Blob([JSON.stringify(mission, null, 2)], {type: 'application/json'});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'mission.json';
  a.click();
  URL.revokeObjectURL(a.href);
};
document.getElementById('btnLoad').onclick = () => document.getElementById('fileInput').click();
document.getElementById('fileInput').onchange = (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const r = new FileReader();
  r.onload = () => {
    try {
      const m = JSON.parse(r.result);
      if (m.autoFlightSpeed) document.getElementById('set-auto').value = m.autoFlightSpeed;
      if (m.maxFlightSpeed) document.getElementById('set-max').value = m.maxFlightSpeed;
      if (m.finishedAction) document.getElementById('set-finish').value = m.finishedAction;
      if (m.headingMode) document.getElementById('set-heading').value = m.headingMode;
      if (m.flightPathMode) document.getElementById('set-path').value = m.flightPathMode;
      if (m.repeatTimes) document.getElementById('set-repeat').value = m.repeatTimes;
      state.waypoints = (m.waypoints || []).map(w => ({
        lat: +w.lat, lng: +w.lon, alt: +w.alt, action: w.action || 'none'
      }));
      redraw();
      log('loaded ' + state.waypoints.length + ' waypoints', 'sys');
    } catch (err) {
      log('bad mission file: ' + err.message, 'err');
    }
  };
  r.readAsText(file);
  e.target.value = '';
};

connect();
</script>
</body>
</html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "M2ilda/1.0"

    def log_message(self, *args):
        pass

    def do_GET(self):
        upgrade = self.headers.get("Upgrade", "").lower()
        if upgrade == "websocket":
            if self.path.startswith("/ws/cmd"):
                serve_ws(self, "cmd")
            elif self.path.startswith("/ws/video"):
                serve_ws(self, "video")
            else:
                self.send_error(404)
            return

        if self.path == "/api/adb":
            body = json.dumps(adb_state).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/api/stream":
            body = json.dumps(stream_state).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path in ("/", "/index.html"):
            body = HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path in ("/waypoints", "/waypoints.html"):
            body = WAYPOINT_HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path in ("/jmuxer.min.js", "/leaflet.js", "/leaflet.css",
                           "/leaflet-images/marker-icon.png",
                           "/leaflet-images/marker-icon-2x.png",
                           "/leaflet-images/marker-shadow.png",
                           "/leaflet-images/layers.png",
                           "/leaflet-images/layers-2x.png"):
            rel = self.path.lstrip("/")
            # basename() keeps a crafted "../" path from escaping webassets.
            asset = os.path.join(
                os.path.dirname(os.path.abspath(__file__)), "webassets",
                *[os.path.basename(part) for part in rel.split("/")])
            ctype = ("text/css" if rel.endswith(".css")
                     else "image/png" if rel.endswith(".png")
                     else "application/javascript")
            try:
                with open(asset, "rb") as fh:
                    body = fh.read()
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except OSError:
                self.send_error(404)
        else:
            self.send_error(404)

    do_POST = do_GET


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def open_browser(url):
    try:
        webbrowser.open(url)
    except Exception as e:
        print("[!] Could not auto-open browser: %s" % e, flush=True)


def main():
    global phone_host, phone_how
    parser = argparse.ArgumentParser(
        description="M2ilda: web ground control for the DJI Mavic 2 Enterprise.")
    parser.add_argument("--host", default=None,
                        help="Phone IP (auto-detected over USB if omitted)")
    parser.add_argument("--port", type=int, default=DEFAULT_HTTP_PORT,
                        help="Web UI port (default %d)" % DEFAULT_HTTP_PORT)
    args = parser.parse_args()

    phone_host, phone_how = args.host, None
    if not phone_host:
        phone_host, phone_how = gs.find_phone_host()
        if not phone_host:
            print("[!] Could not auto-detect the phone. Is it plugged in over USB?")
            print("    Pass --host <phone-ip> to set it manually.")
            sys.exit(1)
    print("[*] Phone: %s (%s)" % (phone_host, phone_how))

    threading.Thread(target=cmd_phone_loop, daemon=True).start()
    threading.Thread(target=vid_phone_loop, daemon=True).start()
    threading.Thread(target=telemetry_poller, daemon=True).start()
    threading.Thread(target=adb_poller, daemon=True).start()
    threading.Thread(target=stream_poller, daemon=True).start()

    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    url = "http://127.0.0.1:%d" % args.port
    print("[*] Web UI running: %s" % url)
    print("[*] Press Ctrl+C to stop.")
    threading.Timer(0.8, open_browser, args=(url,)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[!] Shutting down.")
    finally:
        global run
        run = False
        httpd.server_close()


if __name__ == "__main__":
    main()