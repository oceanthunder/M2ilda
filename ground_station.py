#!/usr/bin/env python3
"""
Ground-station client for the M2ilda Android bridge.

The controller runs the M2ilda app, which opens a TCP listener on port 5555.
This script connects to the phone (over the same WiFi/LAN) and sends commands
which the app relays to the drone through the DJI Mobile SDK.

Usage:
    python3 ground_station.py            # auto-detects the phone over USB
    python3 ground_station.py --auto <command>
    python3 ground_station.py --host 192.168.x.x   # force a specific host

Auto-detection (in order):
    1. adb USB forward  -> connects to the phone at 127.0.0.1:PORT
    2. USB-tether scan  -> finds the phone on the enp*/usb* interface

Available commands:
    takeoff  land  up  down  fwd  back  left  right  rotl  rotr  photo  rec
    stop  status  gohome
"""

import argparse
import ipaddress
import re
import shutil
import socket
import subprocess
import sys
import threading
import time

DEFAULT_PORT = 5555
VIDEO_PORT = 5556

COMMANDS = ["takeoff", "land", "up", "down", "fwd", "back", "left", "right",
            "rotl", "rotr", "photo", "rec", "stop", "status", "gohome"]


def _port_open(host, port, timeout=0.8):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _rndis_candidates():
    """Likely phone IPs derived from USB-tether (RNDIS) interfaces.

    When a phone shares its connection over USB, Linux gets an enp*/usb*
    interface (typically 192.168.42.0/24) and the phone itself is that
    network's host/gateway.
    """
    cands = []
    try:
        addrs = subprocess.run(["ip", "-o", "-4", "addr", "show"],
                               capture_output=True, text=True, timeout=3).stdout
        routes = subprocess.run(["ip", "route"], capture_output=True,
                                text=True, timeout=3).stdout
    except (OSError, subprocess.SubprocessError):
        return cands

    defaults = {}
    for m in re.finditer(r"default via (\S+) dev (\S+)", routes):
        defaults[m.group(2)] = m.group(1)

    for ln in addrs.splitlines():
        parts = ln.split()
        if len(parts) < 4 or parts[2] != "inet":
            continue
        iface, cidr = parts[1].rstrip(":"), parts[3]
        name = iface.lower()
        if not (name.startswith("enp") or name.startswith("usb") or
                name.startswith("eth") or name.startswith("wwan") or
                "rndis" in name):
            continue
        try:
            net = ipaddress.ip_interface(cidr).network
        except ValueError:
            continue
        if not net.is_private or net.prefixlen > 24:
            continue
        if iface in defaults:
            cands.append(defaults[iface])
        base = str(net.network_address).rsplit(".", 1)[0] + "."
        for octet in ("129", "2", "1"):
            ip = base + octet
            if ip not in cands:
                cands.append(ip)
    return cands


def find_phone_host(port=DEFAULT_PORT, video_port=VIDEO_PORT):
    """Locate the phone without a manual IP.

    Returns (host, how). Falls back through strategies until one finds a
    reachable bridge, or (None, None) if nothing is attached.
    """
    adb = shutil.which("adb")
    if adb:
        try:
            out = subprocess.run([adb, "devices"], capture_output=True,
                                 text=True, timeout=5).stdout
            devices = [l.split()[0] for l in out.splitlines()[1:]
                       if l.strip() and not l.lstrip().startswith("*") and
                       "device" in l]
            if devices:
                d = devices[0]
                # Forward the command AND video ports so every USB-attached
                # phone is reachable at 127.0.0.1 regardless of its IP.
                for p in {port, video_port}:
                    subprocess.run([adb, "forward", "tcp:%d" % p,
                                    "tcp:%d" % p],
                                   check=True, capture_output=True, timeout=5)
                # Implicit runtime permissions (kiosk-style, no user prompts).
                for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
                             "READ_PHONE_STATE", "READ_EXTERNAL_STORAGE",
                             "WRITE_EXTERNAL_STORAGE", "RECORD_AUDIO"):
                    subprocess.run([adb, "-s", d, "shell", "pm", "grant",
                                    "com.example.m2ilda",
                                    "android.permission.%s" % perm],
                                   capture_output=True, timeout=5)
                return "127.0.0.1", "adb USB forward (%s)" % d
        except (OSError, subprocess.SubprocessError):
            pass
    for cand in _rndis_candidates():
        if _port_open(cand, port):
            return cand, "USB tether interface"
    return None, None


class GroundStation:
    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.sock = None

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=5)
        print(f"[*] Connected to {self.host}:{self.port}")
        self.sock.settimeout(None)
        return self.sock

    def send(self, command):
        if command not in COMMANDS:
            print(f"[!] Unknown command: {command}")
            return
        if self.sock is None:
            print("[!] Not connected")
            return
        try:
            self.sock.sendall((command + "\n").encode())
            # Wait briefly for the ack (OK:command) or telemetry push
            self.sock.settimeout(2.0)
            try:
                while True:
                    line = self.sock.recv(4096).decode()
                    if not line:
                        break
                    for part in line.splitlines():
                        print(f"[<-] {part}")
                    # Only wait for one response batch on interactive send
                    break
            except socket.timeout:
                pass
            self.sock.settimeout(None)
        except (ConnectionError, OSError) as e:
            print(f"[!] Send failed: {e}")

    def interactive(self):
        print("[*] Interactive mode. Type 'help' for commands, 'quit' to exit.\n")
        while True:
            try:
                cmd = input("drone> ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                print("\n[!] Quitting")
                break
            if not cmd:
                continue
            if cmd == "help":
                print("Commands: " + ", ".join(COMMANDS))
            elif cmd in ("quit", "exit"):
                print("[!] Quitting")
                break
            else:
                self.send(cmd)

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass


def main():
    parser = argparse.ArgumentParser(
        description="Command M2ilda (DJI Mavic 2 Enterprise) from Linux via the "
                    "Android bridge app.")
    parser.add_argument("--host", default=None,
                        help="Phone's IP on the LAN (auto-detected over USB if omitted)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--auto", metavar="CMD", choices=COMMANDS,
                        help="Send a single command and exit")
    args = parser.parse_args()

    host, how = args.host, None
    if not host:
        host, how = find_phone_host(args.port)
        if host:
            print(f"[*] Auto-detected phone at {host}:{args.port} ({how})")
    if not host:
        print("[!] Could not auto-detect the phone. Is it plugged in over USB?")
        print("    Pass --host <phone-ip> to set it manually.")
        sys.exit(1)

    gs = GroundStation(host, args.port)
    try:
        gs.connect()
    except OSError as e:
        print(f"[!] Could not connect to {args.host}:{args.port} - {e}")
        print("    Is the app open on the phone and are both devices on the same network?")
        sys.exit(1)

    try:
        if args.auto:
            gs.send(args.auto)
        else:
            gs.interactive()
    finally:
        gs.close()


if __name__ == "__main__":
    main()
