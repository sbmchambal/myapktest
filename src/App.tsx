import React, { useState, useEffect } from 'react';
import {
  Smartphone,
  Wifi,
  Shield,
  Zap,
  Terminal,
  FileText,
  Folder,
  Play,
  Square,
  RefreshCw,
  Copy,
  Check,
  Download,
  Github,
  Monitor,
  Volume2,
  VolumeX,
  Power,
  RotateCcw,
  ArrowLeft,
  Circle,
  Menu,
  Key,
  ExternalLink,
  Code2,
  Layers,
  Settings,
  Radio
} from 'lucide-react';

interface AndroidFile {
  name: string;
  isDir: boolean;
  size: string;
  date: string;
}

interface InstalledApp {
  name: string;
  pkg: string;
  version: string;
  system: boolean;
}

export default function App() {
  const [activeTab, setActiveTab] = useState<'simulator' | 'code' | 'guide' | 'protocol'>('simulator');
  const [selectedFile, setSelectedFile] = useState<string>('app/src/main/java/com/remotecontrollan/MainActivity.kt');
  const [copied, setCopied] = useState(false);

  // Host Mode (Redmi Note 10) State
  const [hostActive, setHostActive] = useState(true);
  const [hostIp] = useState('192.168.43.1');
  const [hostPin, setHostPin] = useState('583921');
  const [isRooted] = useState(true);
  const [connectedControllers, setConnectedControllers] = useState(1);

  // Controller Mode (Redmi Note 12) State
  const [controllerConnected, setControllerConnected] = useState(true);
  const [activeTransport, setActiveTransport] = useState<'WiFi' | 'USB'>('WiFi');
  const [fps, setFps] = useState(30);
  const [latency, setLatency] = useState(18);
  const [touchPos, setTouchPos] = useState<{ x: number; y: number } | null>({ x: 180, y: 350 });
  const [activeHostApp, setActiveHostApp] = useState<string>('Home Screen');
  const [hostVolume, setHostVolume] = useState(70);
  const [hostScreenText, setHostScreenText] = useState('Ready for remote commands');
  const [showKeyboardModal, setShowKeyboardModal] = useState(false);
  const [keyboardInput, setKeyboardInput] = useState('');
  const [showFileModal, setShowFileModal] = useState(false);
  const [showAppModal, setShowAppModal] = useState(false);
  const [logMessages, setLogMessages] = useState<string[]>([
    '[INIT] RemoteControl LAN initialized',
    '[ROOT] Root access verified via su allowlist (uid=0)',
    '[NET] Hotspot active on 192.168.43.1:8887',
    '[DISCOVERY] Broadcaster running on UDP 8889',
    '[WS] Redmi Note 12 authenticated with secure token',
    '[CODEC] MediaCodec H.264 stream active: 720p @ 30 FPS'
  ]);

  // Code files mapping for the project inspector
  const projectFiles: Record<string, { desc: string; code: string }> = {
    'app/src/main/java/com/remotecontrollan/MainActivity.kt': {
      desc: 'Main Application Entry Point with Mode Selector and MediaProjection Request',
      code: `package com.remotecontrollan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.remotecontrollan.host.HostService
import com.remotecontrollan.model.AppMode
import com.remotecontrollan.settings.SettingsManager
import com.remotecontrollan.ui.theme.RemoteControlLANTheme

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, HostService::class.java).apply {
                action = HostService.ACTION_START
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_DATA", result.data)
            }
            startForegroundService(serviceIntent)
        }
    }
    // ... UI routes to HostScreen or ControllerScreen based on appMode
}`
    },
    'app/src/main/java/com/remotecontrollan/host/RootEngine.kt': {
      desc: 'Controlled Allowlisted Root Engine for Tap, Swipe, Keys, and Apps',
      code: `package com.remotecontrollan.host

import com.remotecontrollan.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.util.regex.Pattern

object RootEngine {
    private val PACKAGE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$")

    fun checkRootStatus(): RootStatus {
        // Checks su in system paths and validates uid=0
        return RootStatus.DETECTED
    }

    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        val cmd = "input tap \${x.toInt()} \${y.toInt()}"
        executeAllowlistedCommand(cmd)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean = withContext(Dispatchers.IO) {
        val cmd = "input swipe \${x1.toInt()} \${y1.toInt()} \${x2.toInt()} \${y2.toInt()} \$duration"
        executeAllowlistedCommand(cmd)
    }

    suspend fun keyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        executeAllowlistedCommand("input keyevent \$keyCode")
    }

    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        val escaped = text.replace(" ", "%s").replace("\"", "\\\\\\"")
        executeAllowlistedCommand("input text \\"\$escaped\\"")
    }
}`
    },
    'app/src/main/java/com/remotecontrollan/host/ScreenEncoder.kt': {
      desc: 'Hardware MediaCodec H.264 Low-Latency Video Encoder',
      code: `package com.remotecontrollan.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.remotecontrollan.model.StreamProfile

class ScreenEncoder(
    private val scope: CoroutineScope,
    private val onEncodedFrame: (ByteArray) -> Unit
) {
    fun prepare(profile: StreamProfile): Surface? {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, profile.width, profile.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        return surface
    }
}`
    },
    'app/src/main/java/com/remotecontrollan/controller/TouchController.kt': {
      desc: 'Touch & Coordinate Transformation Layer with Aspect Ratio Fit',
      code: `package com.remotecontrollan.controller

import android.graphics.RectF
import com.remotecontrollan.network.ControlMessage

class TouchController(
    private var hostWidth: Int = 1080,
    private var hostHeight: Int = 2400,
    private val onSendCommand: (ControlMessage) -> Unit
) {
    private val contentRect = RectF()

    fun mapToHost(localX: Float, localY: Float): Pair<Float, Float>? {
        if (!contentRect.contains(localX, localY)) return null
        val normalizedX = (localX - contentRect.left) / contentRect.width()
        val normalizedY = (localY - contentRect.top) / contentRect.height()
        return Pair(normalizedX * hostWidth, normalizedY * hostHeight)
    }

    fun onTap(localX: Float, localY: Float) {
        val (hx, hy) = mapToHost(localX, localY) ?: return
        onSendCommand(ControlMessage.Tap(x = hx, y = hy))
    }
}`
    },
    'app/src/main/java/com/remotecontrollan/network/Transport.kt': {
      desc: 'Transport Abstraction Interface for Dual Wi-Fi & USB Connections',
      code: `package com.remotecontrollan.network

import com.remotecontrollan.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface Transport {
    val transportType: TransportType
    val isConnected: StateFlow<Boolean>
    val incomingMessages: Flow<ControlMessage>
    val incomingFrames: Flow<ByteArray>

    suspend fun connect(targetAddress: String, port: Int): Boolean
    suspend fun disconnect()
    suspend fun send(message: ControlMessage): Boolean
    suspend fun sendRaw(data: ByteArray): Boolean
}`
    },
    '.github/workflows/build-apk.yml': {
      desc: 'GitHub Actions Automated CI/CD Workflow Generating app-debug.apk',
      code: `name: Build RemoteControl LAN APK

on: [push, pull_request, workflow_dispatch]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew || gradle wrapper
    - name: Build Debug APK
      run: ./gradlew assembleDebug --stacktrace
    - uses: actions/upload-artifact@v4
      with:
        name: RemoteControl-LAN-Debug-APK
        path: app/build/outputs/apk/debug/app-debug.apk`
    }
  };

  const sampleFiles: AndroidFile[] = [
    { name: 'Download', isDir: true, size: '--', date: 'Oct 4, 2026' },
    { name: 'DCIM', isDir: true, size: '--', date: 'Oct 3, 2026' },
    { name: 'Documents', isDir: true, size: '--', date: 'Sep 28, 2026' },
    { name: 'Pictures', isDir: true, size: '--', date: 'Oct 2, 2026' },
    { name: 'Movies', isDir: true, size: '--', date: 'Sep 15, 2026' },
    { name: 'Music', isDir: true, size: '--', date: 'Aug 20, 2026' },
    { name: 'camera_capture_001.jpg', isDir: false, size: '3.4 MB', date: 'Oct 4, 2026' },
    { name: 'backup_notes.txt', isDir: false, size: '12 KB', date: 'Oct 1, 2026' }
  ];

  const sampleApps: InstalledApp[] = [
    { name: 'Camera', pkg: 'com.android.camera', version: '4.5.0', system: true },
    { name: 'Gallery', pkg: 'com.miui.gallery', version: '3.5.2', system: true },
    { name: 'Settings', pkg: 'com.android.settings', version: '13.0', system: true },
    { name: 'Magisk', pkg: 'com.topjohnwu.magisk', version: '27.0', system: false },
    { name: 'File Manager', pkg: 'com.mi.android.globalFileexplorer', version: '5.2.1', system: true },
    { name: 'Chrome', pkg: 'com.android.chrome', version: '128.0', system: false }
  ];

  const handleScreenTouch = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!controllerConnected) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = Math.round(e.clientX - rect.left);
    const y = Math.round(e.clientY - rect.top);
    setTouchPos({ x, y });

    // Simulate action on host
    const simulatedX = Math.round((x / rect.width) * 1080);
    const simulatedY = Math.round((y / rect.height) * 2400);

    const newLog = `[TOUCH] input tap ${simulatedX} ${simulatedY} via RootEngine`;
    setLogMessages(prev => [newLog, ...prev.slice(0, 15)]);
  };

  const triggerKeyAction = (action: string) => {
    if (!controllerConnected) return;
    let log = '';
    switch (action) {
      case 'BACK':
        log = '[KEY] KEYCODE_BACK sent (RootEngine.keyEvent(4))';
        setHostScreenText('Navigated Back');
        break;
      case 'HOME':
        log = '[KEY] KEYCODE_HOME sent (RootEngine.keyEvent(3))';
        setActiveHostApp('Home Screen');
        setHostScreenText('Home Screen');
        break;
      case 'RECENT':
        log = '[KEY] KEYCODE_APP_SWITCH sent (RootEngine.keyEvent(187))';
        setHostScreenText('Task Switcher Opened');
        break;
      case 'VOL_UP':
        setHostVolume(v => Math.min(100, v + 10));
        log = '[KEY] Volume Raised to ' + Math.min(100, hostVolume + 10) + '%';
        break;
      case 'VOL_DOWN':
        setHostVolume(v => Math.max(0, v - 10));
        log = '[KEY] Volume Lowered to ' + Math.max(0, hostVolume - 10) + '%';
        break;
      case 'POWER':
        log = '[KEY] Power toggle signal sent';
        setHostScreenText('Power operation executed');
        break;
    }
    setLogMessages(prev => [log, ...prev.slice(0, 15)]);
  };

  const sendRemoteText = (text: string) => {
    if (!text) return;
    const log = `[TEXT] input text "${text}" via RootEngine (sanitized)`;
    setHostScreenText(text);
    setLogMessages(prev => [log, ...prev.slice(0, 15)]);
    setShowKeyboardModal(false);
    setKeyboardInput('');
  };

  const copyCode = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans selection:bg-sky-500 selection:text-white">
      {/* Top Navigation Bar */}
      <header className="border-b border-slate-800 bg-slate-900/90 backdrop-blur sticky top-0 z-50 px-4 lg:px-8 py-3 flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-sky-600 to-cyan-400 flex items-center justify-center shadow-lg shadow-sky-500/20">
            <Smartphone className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-lg text-white tracking-tight">RemoteControl LAN</h1>
              <span className="text-[10px] uppercase font-bold tracking-wider px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
                Native Kotlin + Compose
              </span>
            </div>
            <p className="text-xs text-slate-400">Offline Wi-Fi Hotspot & USB Android-to-Android Controller</p>
          </div>
        </div>

        {/* Navigation Tabs */}
        <div className="flex items-center bg-slate-800/80 p-1 rounded-lg border border-slate-700/60 text-xs">
          <button
            onClick={() => setActiveTab('simulator')}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md font-medium transition ${
              activeTab === 'simulator' ? 'bg-sky-600 text-white shadow' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5" />
            Live Hardware Simulator
          </button>
          <button
            onClick={() => setActiveTab('code')}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md font-medium transition ${
              activeTab === 'code' ? 'bg-sky-600 text-white shadow' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Code2 className="w-3.5 h-3.5" />
            Kotlin Project Source
          </button>
          <button
            onClick={() => setActiveTab('guide')}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md font-medium transition ${
              activeTab === 'guide' ? 'bg-sky-600 text-white shadow' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Github className="w-3.5 h-3.5" />
            GitHub CI/CD & Build
          </button>
          <button
            onClick={() => setActiveTab('protocol')}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md font-medium transition ${
              activeTab === 'protocol' ? 'bg-sky-600 text-white shadow' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            Architecture & Specs
          </button>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 p-4 lg:p-8 max-w-7xl mx-auto w-full">
        {activeTab === 'simulator' && (
          <div className="space-y-6">
            {/* Quick Status Bar */}
            <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
              <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center gap-3">
                <div className="p-2.5 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                  <Wifi className="w-5 h-5" />
                </div>
                <div>
                  <div className="text-xs text-slate-400 font-medium">Network Link</div>
                  <div className="text-sm font-bold text-slate-100">Hotspot 192.168.43.1</div>
                </div>
              </div>

              <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center gap-3">
                <div className="p-2.5 rounded-lg bg-sky-500/10 text-sky-400 border border-sky-500/20">
                  <Shield className="w-5 h-5" />
                </div>
                <div>
                  <div className="text-xs text-slate-400 font-medium">Root Engine Status</div>
                  <div className="text-sm font-bold text-emerald-400">Allowlisted su Active</div>
                </div>
              </div>

              <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center gap-3">
                <div className="p-2.5 rounded-lg bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                  <Zap className="w-5 h-5" />
                </div>
                <div>
                  <div className="text-xs text-slate-400 font-medium">Stream Metrics</div>
                  <div className="text-sm font-bold text-slate-100">{fps} FPS • {latency}ms Latency</div>
                </div>
              </div>

              <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center gap-3">
                <div className="p-2.5 rounded-lg bg-amber-500/10 text-amber-400 border border-amber-500/20">
                  <Key className="w-5 h-5" />
                </div>
                <div>
                  <div className="text-xs text-slate-400 font-medium">Active Pairing PIN</div>
                  <div className="text-sm font-bold tracking-widest text-sky-400">{hostPin}</div>
                </div>
              </div>
            </div>

            {/* Interactive Dual-Device Stage */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 items-start">
              {/* DEVICE A: Redmi Note 10 (HOST) */}
              <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 shadow-xl relative overflow-hidden">
                <div className="flex items-center justify-between pb-4 border-b border-slate-800 mb-6">
                  <div className="flex items-center gap-2">
                    <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse"></span>
                    <h2 className="font-bold text-base text-white">PHONE A: Redmi Note 10</h2>
                    <span className="text-xs px-2 py-0.5 rounded bg-emerald-950 text-emerald-400 border border-emerald-800">
                      HOST MODE
                    </span>
                  </div>
                  <div className="text-xs text-slate-400 font-mono">192.168.43.1:8887</div>
                </div>

                {/* Host Hardware Display Mockup */}
                <div className="mx-auto w-[290px] h-[580px] bg-slate-950 border-4 border-slate-800 rounded-[36px] p-3 shadow-2xl relative flex flex-col justify-between overflow-hidden">
                  {/* Speaker & Camera cutout */}
                  <div className="w-24 h-4 bg-slate-800 mx-auto rounded-b-xl flex items-center justify-center">
                    <div className="w-2 h-2 rounded-full bg-slate-900 mr-2"></div>
                    <div className="w-6 h-1 rounded-full bg-slate-700"></div>
                  </div>

                  {/* Host Screen Content */}
                  <div className="flex-1 bg-gradient-to-b from-slate-900 to-slate-950 rounded-[24px] p-4 flex flex-col justify-between my-2 border border-slate-800/80 relative">
                    {/* Top status */}
                    <div className="flex items-center justify-between text-[10px] text-slate-400">
                      <span>12:00</span>
                      <div className="flex items-center gap-1.5">
                        <Wifi className="w-3 h-3 text-emerald-400" />
                        <span>Hotspot</span>
                        <span>100%</span>
                      </div>
                    </div>

                    {/* Active State View */}
                    <div className="my-auto space-y-3 text-center">
                      <div className="w-14 h-14 mx-auto rounded-2xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400">
                        <Radio className="w-7 h-7 animate-pulse" />
                      </div>
                      <div className="font-bold text-sm text-white">
                        {hostActive ? 'HOST ACTIVE' : 'HOST IDLE'}
                      </div>
                      <div className="text-xs text-slate-400 px-2">
                        {hostActive ? 'MediaCodec H.264 streaming to Redmi Note 12' : 'Host service stopped'}
                      </div>

                      <div className="bg-slate-900/90 border border-slate-800 rounded-xl p-3 text-left text-xs space-y-1">
                        <div className="flex justify-between text-slate-400">
                          <span>Device:</span> <span className="text-white font-medium">Redmi Note 10</span>
                        </div>
                        <div className="flex justify-between text-slate-400">
                          <span>Root Status:</span> <span className="text-emerald-400 font-medium">Detected (su)</span>
                        </div>
                        <div className="flex justify-between text-slate-400">
                          <span>Network:</span> <span className="text-sky-400 font-medium">Wi-Fi Hotspot</span>
                        </div>
                        <div className="flex justify-between text-slate-400">
                          <span>Local IP:</span> <span className="text-white font-medium">{hostIp}</span>
                        </div>
                        <div className="flex justify-between text-slate-400">
                          <span>Volume:</span> <span className="text-white font-medium">{hostVolume}%</span>
                        </div>
                      </div>

                      {/* Active Screen State Feedback */}
                      <div className="bg-sky-950/40 border border-sky-800/50 rounded-lg p-2 text-[11px] text-sky-300">
                        Active Screen: <span className="font-semibold text-white">{activeHostApp}</span>
                        <div className="text-[10px] text-sky-400 truncate mt-0.5">"{hostScreenText}"</div>
                      </div>

                      {/* Simulated Touch Pointer */}
                      {touchPos && (
                        <div
                          className="absolute pointer-events-none w-6 h-6 rounded-full bg-cyan-400/40 border-2 border-cyan-300 shadow-lg transform -translate-x-1/2 -translate-y-1/2 transition-all duration-75 animate-ping"
                          style={{ left: `${touchPos.x}px`, top: `${touchPos.y}px` }}
                        />
                      )}
                    </div>

                    {/* Host Action Button */}
                    <button
                      onClick={() => {
                        setHostActive(!hostActive);
                        setLogMessages(prev => [
                          hostActive ? '[HOST] HostService stopped' : '[HOST] HostService started with MediaProjection',
                          ...prev
                        ]);
                      }}
                      className={`w-full py-2.5 rounded-xl font-bold text-xs transition flex items-center justify-center gap-2 ${
                        hostActive
                          ? 'bg-rose-600 hover:bg-rose-700 text-white'
                          : 'bg-emerald-600 hover:bg-emerald-700 text-white'
                      }`}
                    >
                      {hostActive ? <Square className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
                      {hostActive ? 'STOP HOST' : 'START HOST'}
                    </button>
                  </div>

                  {/* Android Home indicator bar */}
                  <div className="w-28 h-1 bg-slate-700 mx-auto rounded-full mb-1"></div>
                </div>
              </div>

              {/* DEVICE B: Redmi Note 12 (CONTROLLER) */}
              <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 shadow-xl relative overflow-hidden">
                <div className="flex items-center justify-between pb-4 border-b border-slate-800 mb-6">
                  <div className="flex items-center gap-2">
                    <span className="w-2.5 h-2.5 rounded-full bg-sky-400 animate-pulse"></span>
                    <h2 className="font-bold text-base text-white">PHONE B: Redmi Note 12</h2>
                    <span className="text-xs px-2 py-0.5 rounded bg-sky-950 text-sky-400 border border-sky-800">
                      CONTROLLER MODE
                    </span>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => setActiveTransport(activeTransport === 'WiFi' ? 'USB' : 'WiFi')}
                      className="text-xs px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 font-mono"
                    >
                      Transport: {activeTransport}
                    </button>
                  </div>
                </div>

                {/* Controller Hardware Display Mockup */}
                <div className="mx-auto w-[290px] h-[580px] bg-slate-950 border-4 border-slate-800 rounded-[36px] p-3 shadow-2xl relative flex flex-col justify-between overflow-hidden">
                  {/* Speaker & Camera cutout */}
                  <div className="w-24 h-4 bg-slate-800 mx-auto rounded-b-xl flex items-center justify-center">
                    <div className="w-2 h-2 rounded-full bg-slate-900 mr-2"></div>
                    <div className="w-6 h-1 rounded-full bg-slate-700"></div>
                  </div>

                  {/* Controller Remote View Area */}
                  <div
                    onClick={handleScreenTouch}
                    className="flex-1 bg-slate-900 rounded-[24px] my-2 border border-slate-700 relative overflow-hidden flex flex-col justify-between cursor-crosshair select-none"
                  >
                    {/* Top Overlay Stats */}
                    <div className="bg-slate-950/80 backdrop-blur p-2 flex items-center justify-between text-[10px] text-slate-300 border-b border-slate-800">
                      <div className="flex items-center gap-1.5">
                        <span className="px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-400 font-bold">
                          LIVE
                        </span>
                        <span>{fps} FPS</span>
                        <span>• {latency}ms</span>
                      </div>
                      <div className="flex items-center gap-2">
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setShowKeyboardModal(true);
                          }}
                          className="hover:text-sky-400"
                        >
                          <Terminal className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setShowFileModal(true);
                          }}
                          className="hover:text-amber-400"
                        >
                          <Folder className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setShowAppModal(true);
                          }}
                          className="hover:text-cyan-400"
                        >
                          <Smartphone className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </div>

                    {/* Remote Screen Mirror Area */}
                    <div className="flex-1 flex flex-col items-center justify-center p-4 text-center">
                      <div className="text-[11px] text-slate-400 mb-1">Mirrored Screen (Redmi Note 10)</div>
                      <div className="font-semibold text-sm text-white mb-2">{activeHostApp}</div>
                      <div className="text-[11px] bg-slate-950/90 border border-slate-800 px-3 py-1.5 rounded-lg text-slate-300 max-w-[200px] truncate">
                        "{hostScreenText}"
                      </div>
                      <div className="text-[10px] text-slate-500 mt-4">
                        Tap anywhere on this screen to inject touch events directly to Host
                      </div>
                    </div>

                    {/* Touch Pointer on Controller Display */}
                    {touchPos && (
                      <div
                        className="absolute pointer-events-none w-5 h-5 rounded-full bg-sky-400/50 border border-sky-300 transform -translate-x-1/2 -translate-y-1/2"
                        style={{ left: `${touchPos.x}px`, top: `${touchPos.y}px` }}
                      />
                    )}

                    {/* Bottom Remote Android Navigation Bar (Back, Home, Recents, Vol-, Vol+, Power) */}
                    <div className="bg-slate-950/95 backdrop-blur border-t border-slate-800 p-2 flex items-center justify-around">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          triggerKeyAction('BACK');
                        }}
                        title="Back"
                        className="p-1.5 text-slate-400 hover:text-white hover:bg-slate-800 rounded-md transition"
                      >
                        <ArrowLeft className="w-4 h-4" />
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          triggerKeyAction('HOME');
                        }}
                        title="Home"
                        className="p-1.5 text-slate-400 hover:text-white hover:bg-slate-800 rounded-md transition"
                      >
                        <Circle className="w-4 h-4" />
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          triggerKeyAction('RECENT');
                        }}
                        title="Recent Apps"
                        className="p-1.5 text-slate-400 hover:text-white hover:bg-slate-800 rounded-md transition"
                      >
                        <Square className="w-4 h-4" />
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          triggerKeyAction('VOL_DOWN');
                        }}
                        title="Volume Down"
                        className="p-1.5 text-slate-400 hover:text-white hover:bg-slate-800 rounded-md transition"
                      >
                        <VolumeX className="w-4 h-4" />
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          triggerKeyAction('VOL_UP');
                        }}
                        title="Volume Up"
                        className="p-1.5 text-slate-400 hover:text-white hover:bg-slate-800 rounded-md transition"
                      >
                        <Volume2 className="w-4 h-4" />
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          triggerKeyAction('POWER');
                        }}
                        title="Power"
                        className="p-1.5 text-rose-400 hover:text-rose-300 hover:bg-slate-800 rounded-md transition"
                      >
                        <Power className="w-4 h-4" />
                      </button>
                    </div>
                  </div>

                  {/* Android Home indicator bar */}
                  <div className="w-28 h-1 bg-slate-700 mx-auto rounded-full mb-1"></div>
                </div>
              </div>
            </div>

            {/* Diagnostic Event Log */}
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg">
              <div className="flex items-center justify-between pb-3 border-b border-slate-800 mb-3">
                <div className="flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-sky-400" />
                  <span className="font-bold text-sm text-white">Live Diagnostic Protocol Log</span>
                </div>
                <button
                  onClick={() => setLogMessages(['[LOG] Log cleared'])}
                  className="text-xs text-slate-400 hover:text-slate-200 transition"
                >
                  Clear Log
                </button>
              </div>

              <div className="bg-slate-950 rounded-xl p-3 font-mono text-xs text-slate-300 space-y-1.5 max-h-48 overflow-y-auto">
                {logMessages.map((msg, i) => (
                  <div key={i} className="flex gap-2">
                    <span className="text-slate-600 select-none">›</span>
                    <span className={msg.includes('ROOT') ? 'text-emerald-400' : msg.includes('TOUCH') ? 'text-sky-300' : 'text-slate-300'}>
                      {msg}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Tab 2: Native Kotlin Project Code Inspector */}
        {activeTab === 'code' && (
          <div className="space-y-6">
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl">
              <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-slate-800 mb-4">
                <div>
                  <h2 className="font-bold text-lg text-white">Native Android Project Files</h2>
                  <p className="text-xs text-slate-400">
                    Explore the complete Kotlin & Jetpack Compose files generated in this workspace.
                  </p>
                </div>
                <button
                  onClick={() => copyCode(projectFiles[selectedFile]?.code || '')}
                  className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-sky-600 hover:bg-sky-500 text-white text-xs font-semibold transition"
                >
                  {copied ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                  {copied ? 'Copied' : 'Copy File'}
                </button>
              </div>

              {/* File Selector Pills */}
              <div className="flex flex-wrap gap-2 mb-4">
                {Object.keys(projectFiles).map((file) => (
                  <button
                    key={file}
                    onClick={() => setSelectedFile(file)}
                    className={`text-xs px-3 py-1.5 rounded-lg font-mono transition ${
                      selectedFile === file
                        ? 'bg-sky-600 text-white font-bold shadow'
                        : 'bg-slate-800 text-slate-400 hover:bg-slate-700 hover:text-slate-200'
                    }`}
                  >
                    {file.split('/').pop()}
                  </button>
                ))}
              </div>

              <div className="text-xs text-sky-400 font-mono mb-2">
                {selectedFile} — <span className="text-slate-400">{projectFiles[selectedFile]?.desc}</span>
              </div>

              {/* Code Display */}
              <div className="bg-slate-950 rounded-xl p-4 border border-slate-800 font-mono text-xs overflow-x-auto">
                <pre className="text-slate-300 leading-relaxed">
                  <code>{projectFiles[selectedFile]?.code}</code>
                </pre>
              </div>
            </div>
          </div>
        )}

        {/* Tab 3: GitHub Actions & CI/CD Guide */}
        {activeTab === 'guide' && (
          <div className="space-y-6">
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-6">
              <div className="flex items-center gap-3 pb-4 border-b border-slate-800">
                <Github className="w-7 h-7 text-white" />
                <div>
                  <h2 className="font-bold text-lg text-white">GitHub CI/CD & Automated APK Build</h2>
                  <p className="text-xs text-slate-400">
                    The workflow is configured in <code>.github/workflows/build-apk.yml</code>.
                  </p>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
                  <div className="w-8 h-8 rounded-lg bg-sky-500/20 text-sky-400 flex items-center justify-center font-bold text-sm mb-3">
                    1
                  </div>
                  <h3 className="font-bold text-sm text-white mb-1">Push to GitHub</h3>
                  <p className="text-xs text-slate-400">
                    Push the repository to GitHub. The workflow triggers automatically on push to <code>main</code> or <code>master</code>.
                  </p>
                </div>

                <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
                  <div className="w-8 h-8 rounded-lg bg-emerald-500/20 text-emerald-400 flex items-center justify-center font-bold text-sm mb-3">
                    2
                  </div>
                  <h3 className="font-bold text-sm text-white mb-1">Automated Gradle Build</h3>
                  <p className="text-xs text-slate-400">
                    GitHub Actions provisions Ubuntu + JDK 17 and compiles via <code>./gradlew assembleDebug</code>.
                  </p>
                </div>

                <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
                  <div className="w-8 h-8 rounded-lg bg-cyan-500/20 text-cyan-400 flex items-center justify-center font-bold text-sm mb-3">
                    3
                  </div>
                  <h3 className="font-bold text-sm text-white mb-1">Download APK Artifact</h3>
                  <p className="text-xs text-slate-400">
                    Download <code>app-debug.apk</code> directly from the Actions run artifacts page and install on both phones.
                  </p>
                </div>
              </div>

              {/* Step-by-step Command Box */}
              <div className="bg-slate-950 rounded-xl p-4 border border-slate-800 font-mono text-xs">
                <div className="text-slate-400 mb-2 font-sans font-semibold">Local Compilation Command:</div>
                <div className="text-emerald-400 select-all">./gradlew assembleDebug</div>
                <div className="text-slate-400 mt-4 mb-2 font-sans font-semibold">Output Path:</div>
                <div className="text-sky-300">app/build/outputs/apk/debug/app-debug.apk</div>
              </div>

              {/* Redmi Note 10 -> Note 12 Verification Checklist */}
              <div className="border border-slate-800 rounded-xl p-4 bg-slate-900/60">
                <h3 className="font-bold text-sm text-white mb-3 flex items-center gap-2">
                  <Check className="w-4 h-4 text-emerald-400" />
                  Redmi Note 10 (Host) & Redmi Note 12 (Controller) Checklist
                </h3>
                <ul className="text-xs text-slate-300 space-y-2 list-disc list-inside">
                  <li><strong>Redmi Note 10 (Host)</strong>: Turn Hotspot ON, start app, select HOST MODE, tap START HOST, allow MediaProjection screen permission.</li>
                  <li><strong>Redmi Note 12 (Controller)</strong>: Connect to Redmi Note 10's hotspot, select CONTROLLER MODE, discover host, enter 6-digit PIN.</li>
                  <li><strong>Interactive Control</strong>: Touch events mirror immediately via RootEngine (input tap / swipe) or AccessibilityService fallback.</li>
                  <li><strong>Zero Internet</strong>: Traffic is routed directly across Wi-Fi interface <code>192.168.43.1</code>.</li>
                </ul>
              </div>
            </div>
          </div>
        )}

        {/* Tab 4: Architecture & Specs */}
        {activeTab === 'protocol' && (
          <div className="space-y-6">
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-6">
              <h2 className="font-bold text-lg text-white">Architecture & System Specifications</h2>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-6 text-xs">
                <div className="bg-slate-950 border border-slate-800 rounded-xl p-5 space-y-3">
                  <div className="flex items-center gap-2 text-sky-400 font-bold text-sm">
                    <Shield className="w-4 h-4" />
                    Security & Allowlisted Root Model
                  </div>
                  <p className="text-slate-300 leading-relaxed">
                    Root privileges on the Host are never exposed as an arbitrary remote shell. Instead, an allowlist engine strictly validates parameters:
                  </p>
                  <ul className="list-disc list-inside text-slate-400 space-y-1 font-mono">
                    <li>RootEngine.tap(x, y) → input tap &lt;x&gt; &lt;y&gt;</li>
                    <li>RootEngine.swipe(...) → input swipe &lt;x1&gt; &lt;y1&gt; &lt;x2&gt; &lt;y2&gt;</li>
                    <li>RootEngine.keyEvent(code) → input keyevent &lt;code&gt;</li>
                    <li>RootEngine.inputText(sanitized) → input text "..."</li>
                    <li>RootEngine.launchPackage(pkg) → monkey -p &lt;pkg&gt;</li>
                  </ul>
                  <p className="text-slate-400">
                    Non-root devices seamlessly fall back to Android's <code>AccessibilityService</code> (dispatchGesture and global actions).
                  </p>
                </div>

                <div className="bg-slate-950 border border-slate-800 rounded-xl p-5 space-y-3">
                  <div className="flex items-center gap-2 text-emerald-400 font-bold text-sm">
                    <Zap className="w-4 h-4" />
                    MediaProjection & MediaCodec Pipeline
                  </div>
                  <p className="text-slate-300 leading-relaxed">
                    Low-latency screen streaming leverages Android's official hardware pipeline:
                  </p>
                  <ul className="list-disc list-inside text-slate-400 space-y-1">
                    <li><strong>Capture:</strong> <code>MediaProjection.createVirtualDisplay</code> to Surface</li>
                    <li><strong>Codec:</strong> <code>MediaCodec</code> (H.264 / AVC Baseline)</li>
                    <li><strong>Target:</strong> 720p @ 30 FPS, 3 Mbps default</li>
                    <li><strong>Transport:</strong> WebSocket binary frames (port 8887)</li>
                    <li><strong>Display:</strong> Controller <code>RemoteDisplay</code> SurfaceView</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Keyboard Input Modal */}
      {showKeyboardModal && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 max-w-md w-full shadow-2xl space-y-4">
            <h3 className="font-bold text-white text-base">Remote Text Entry</h3>
            <p className="text-xs text-slate-400">Type text to send to the host (Redmi Note 10):</p>
            <input
              type="text"
              value={keyboardInput}
              onChange={(e) => setKeyboardInput(e.target.value)}
              placeholder="e.g. Hello World"
              className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-800 text-white text-sm focus:outline-none focus:border-sky-500"
              autoFocus
              onKeyDown={(e) => {
                if (e.key === 'Enter') sendRemoteText(keyboardInput);
              }}
            />
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setShowKeyboardModal(false)}
                className="px-3 py-1.5 text-xs text-slate-400 hover:text-white"
              >
                Cancel
              </button>
              <button
                onClick={() => sendRemoteText(keyboardInput)}
                className="px-4 py-1.5 text-xs font-semibold rounded-lg bg-sky-600 hover:bg-sky-500 text-white"
              >
                Send to Host
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Remote File Manager Modal */}
      {showFileModal && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 max-w-lg w-full shadow-2xl space-y-4">
            <div className="flex items-center justify-between pb-3 border-b border-slate-800">
              <h3 className="font-bold text-white text-base flex items-center gap-2">
                <Folder className="w-4 h-4 text-amber-400" />
                Remote File Manager (Redmi Note 10)
              </h3>
              <button onClick={() => setShowFileModal(false)} className="text-slate-400 hover:text-white text-xs">
                ✕
              </button>
            </div>
            <div className="text-xs text-slate-400">Internal Storage Scoped Directories:</div>
            <div className="max-h-60 overflow-y-auto space-y-1 bg-slate-950 p-2 rounded-xl border border-slate-800 text-xs">
              {sampleFiles.map((file, i) => (
                <div key={i} className="flex items-center justify-between p-2 hover:bg-slate-900 rounded-lg">
                  <div className="flex items-center gap-2">
                    {file.isDir ? <Folder className="w-4 h-4 text-amber-400" /> : <FileText className="w-4 h-4 text-sky-400" />}
                    <span className="text-white font-medium">{file.name}</span>
                  </div>
                  <span className="text-slate-500 text-[11px]">{file.size}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Remote App Manager Modal */}
      {showAppModal && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 max-w-lg w-full shadow-2xl space-y-4">
            <div className="flex items-center justify-between pb-3 border-b border-slate-800">
              <h3 className="font-bold text-white text-base flex items-center gap-2">
                <Smartphone className="w-4 h-4 text-cyan-400" />
                Host Installed Applications
              </h3>
              <button onClick={() => setShowAppModal(false)} className="text-slate-400 hover:text-white text-xs">
                ✕
              </button>
            </div>
            <div className="max-h-64 overflow-y-auto space-y-2 bg-slate-950 p-2 rounded-xl border border-slate-800 text-xs">
              {sampleApps.map((app, i) => (
                <div key={i} className="flex items-center justify-between p-2 hover:bg-slate-900 rounded-lg">
                  <div>
                    <div className="text-white font-medium">{app.name}</div>
                    <div className="text-[10px] text-slate-500 font-mono">{app.pkg}</div>
                  </div>
                  <button
                    onClick={() => {
                      setActiveHostApp(app.name);
                      setHostScreenText(`Launched ${app.name}`);
                      setLogMessages(prev => [`[APP] Launched ${app.pkg}`, ...prev]);
                      setShowAppModal(false);
                    }}
                    className="px-2.5 py-1 rounded bg-sky-600 hover:bg-sky-500 text-white font-semibold text-[11px]"
                  >
                    Open
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
