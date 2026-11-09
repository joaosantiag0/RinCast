package app.rincast.vr;

import android.Manifest;
import android.app.NativeActivity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The whole app is native (OpenXR + OpenGL ES in librincast.so). This class only
 * provides the few platform services that have no NDK equivalent: HTTPS,
 * runtime permissions, Wi-Fi performance locks and a stable device id.
 * Native code calls these methods through JNI.
 */
public class MainActivity extends NativeActivity {
    static {
        System.loadLibrary("rincast");
    }

    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private InputManager.InputDeviceListener inputDeviceListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
        }
        // A Bluetooth keyboard can disappear without the window losing focus. Release
        // everything it had pressed on the PC instead of leaving Super/Ctrl stuck down.
        final InputManager idm = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (idm != null) {
            inputDeviceListener = new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) { }
                @Override public void onInputDeviceRemoved(int deviceId) { nativeReleaseAllKeys(); }
                @Override public void onInputDeviceChanged(int deviceId) { }
            };
            idm.registerInputDeviceListener(inputDeviceListener, null);
        }
    }

    @Override
    protected void onDestroy() {
        setStreamingLocks(false);
        setUsbNetwork(false);
        final InputManager idm = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (idm != null && inputDeviceListener != null) {
            idm.unregisterInputDeviceListener(inputDeviceListener);
            inputDeviceListener = null;
        }
        super.onDestroy();
    }

    // ---- A USB cable to the PC (Horizon OS 2.5+) ------------------------------------
    // The headset offers the cable as a network of its own: IPv6 link-local, no internet.
    // Asked for once; the user allows it in a system dialog, which only shows while RinCast is
    // in front. Native code polls usbNetworkState().

    private ConnectivityManager.NetworkCallback usbCallback;
    private volatile int usbState = 0;  // 0 off, 1 waiting for a cable, 2 connected, 3 not allowed
    private volatile long usbHandle = 0;
    private volatile String usbInterface = "";
    private volatile int usbKbps = 0;

    public synchronized void setUsbNetwork(boolean on) {
        final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        if (!on) {
            if (usbCallback != null) {
                try { cm.unregisterNetworkCallback(usbCallback); } catch (RuntimeException ignored) { }
                usbCallback = null;
            }
            usbState = 0;
            usbHandle = 0;
            usbInterface = "";
            return;
        }
        if (usbCallback != null) return;
        final NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_USB)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .build();
        usbCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                usbHandle = network.getNetworkHandle();
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp != null && lp.getInterfaceName() != null) usbInterface = lp.getInterfaceName();
                usbState = 2;
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                if (lp.getInterfaceName() != null) usbInterface = lp.getInterfaceName();
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                usbKbps = caps.getLinkDownstreamBandwidthKbps();
            }
            @Override public void onLost(Network network) {
                usbHandle = 0;
                usbState = 1;
            }
            @Override public void onUnavailable() {
                // Declined, or asked while in the background: the request is gone.
                synchronized (MainActivity.this) { usbCallback = null; }
                usbHandle = 0;
                usbState = 3;
            }
        };
        usbState = 1;
        try {
            cm.requestNetwork(request, usbCallback);
        } catch (RuntimeException e) {
            usbCallback = null;
            usbState = 3;
        }
    }

    /** "state,handle,kbps,interface". */
    public String usbNetworkState() {
        return usbState + "," + usbHandle + "," + usbKbps + "," + usbInterface;
    }

    /** Stable random id for this installation (used to recognize the headset). */
    public String getRinCastDeviceId() {
        SharedPreferences prefs = getSharedPreferences("rincast", Context.MODE_PRIVATE);
        String id = prefs.getString("deviceId", null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            prefs.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    public String getDeviceModel() {
        return Build.MODEL;
    }

    public String getFilesPath() {
        return getFilesDir().getAbsolutePath();
    }

    /** The system's language as a BCP 47 tag, e.g. "zh-CN", for the UI's "auto" language. */
    public String getSystemLocale() {
        return android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).toLanguageTag();
    }

    public boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Keeps Wi-Fi in its low-latency mode and the CPU awake while streaming;
     * the multicast lock lets us receive LAN discovery broadcasts.
     */
    public synchronized void setStreamingLocks(boolean enable) {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (enable) {
            if (wifiLock == null && wifi != null) {
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                                                       : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wifi.createWifiLock(mode, "rincast:stream");
                wifiLock.acquire();
            }
            if (multicastLock == null && wifi != null) {
                multicastLock = wifi.createMulticastLock("rincast:discovery");
                multicastLock.acquire();
            }
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rincast:stream");
                wakeLock.acquire();
            }
        } else {
            if (wifiLock != null) { wifiLock.release(); wifiLock = null; }
            if (multicastLock != null) { multicastLock.release(); multicastLock = null; }
            if (wakeLock != null) { wakeLock.release(); wakeLock = null; }
        }
    }

    // ---- Interface sounds and spoken labels -----------------------------------------

    private final AudioTrack[] uiSounds = new AudioTrack[3];
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile String pendingSpeech;

    // A short soft tone: one note per frequency, each with a fast decay.
    private static AudioTrack makeSound(double[] notes, int noteMs, double volume) {
        final int rate = 48000, noteFrames = rate * noteMs / 1000;
        final short[] pcm = new short[noteFrames * notes.length];
        for (int n = 0; n < notes.length; n++) {
            for (int i = 0; i < noteFrames; i++) {
                final double t = (double) i / rate;
                final double envelope = Math.min(1.0, i / 96.0) * Math.exp(-t * 2000.0 / noteMs / 4.0);
                pcm[n * noteFrames + i] = (short) (Math.sin(2 * Math.PI * notes[n] * t) * envelope * volume * 32767);
            }
        }
        final AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.length * 2)
            .setTransferMode(AudioTrack.MODE_STATIC).build();
        track.write(pcm, 0, pcm.length);
        return track;
    }

    public void playUiSound(int kind) {
        try {
            if (uiSounds[0] == null) {
                uiSounds[0] = makeSound(new double[] {1400}, 25, 0.12);
                uiSounds[1] = makeSound(new double[] {660, 990}, 90, 0.2);
                uiSounds[2] = makeSound(new double[] {440, 330}, 130, 0.22);
            }
            final AudioTrack t = uiSounds[kind];
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (RuntimeException e) {
            // Sounds are a nicety: never let them disturb the app.
        }
    }

    /** Asks for those of the permissions (separated by commas) that aren't granted yet. */
    public void requestPermissionList(String list) {
        final java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String p : list.split(",")) {
            if (!p.isEmpty() && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) runOnUiThread(() -> requestPermissions(missing.toArray(new String[0]), 2));
    }

    public void speak(String text) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rincast-ui");
            return;
        }
        // The engine takes a moment to start: say the latest thing once it's there.
        pendingSpeech = text;
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && pendingSpeech != null) tts.speak(pendingSpeech, TextToSpeech.QUEUE_FLUSH, null, "rincast-ui");
            });
        }
    }

    private static boolean isLocalHost(String host) {
        final String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".local") || h.equals("[::1]") || h.startsWith("[fe80:") || h.startsWith("[fd") || h.startsWith("[fc")) return true;
        final String[] p = h.split("\\.");
        if (p.length != 4) return false;
        try {
            final int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Blocking HTTP(S) request, called from a native worker thread.
     * Returns "<status>\n<body>"; status 0 means a network error.
     */
    public String httpRequest(String method, String url, String body, String bearer, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            // Sign-in tokens never travel unencrypted over the internet. Plain http is only
            // for a self-hosted server on the same network.
            final URL parsed = new URL(url);
            if (parsed.getProtocol().equals("http") && !isLocalHost(parsed.getHost())) {
                throw new java.io.IOException("This server needs an https:// address.");
            }
            conn = (HttpURLConnection) parsed.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "RinCast-Headset/1.0");
            if (bearer != null && !bearer.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = "";
            if (is != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                is.close();
                text = out.toString("UTF-8");
            }
            return status + "\n" + text;
        } catch (Exception e) {
            return "0\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- Physical mouse and keyboard forwarding -----------------------------------
    private boolean pointerCaptureRequested = false;
    private float lastMouseX = 0, lastMouseY = 0;

    public void setPointerCapture(boolean enable) {
        pointerCaptureRequested = enable;
        runOnUiThread(() -> {
            if (getWindow() != null && getWindow().getDecorView() != null) {
                if (enable) {
                    getWindow().getDecorView().setOnCapturedPointerListener((view, event) -> handlePointerEvent(event));
                    getWindow().getDecorView().requestPointerCapture();
                } else {
                    getWindow().getDecorView().releasePointerCapture();
                }
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pointerCaptureRequested) {
            if (getWindow() != null && getWindow().getDecorView() != null) {
                getWindow().getDecorView().setOnCapturedPointerListener((view, event) -> handlePointerEvent(event));
                getWindow().getDecorView().requestPointerCapture();
            }
        } else if (!hasFocus) {
            nativeReleaseAllKeys();
        }
    }

    private static int mapMouseButton(int btn) {
        switch (btn) {
            case MotionEvent.BUTTON_PRIMARY: return 0;   // Left
            case MotionEvent.BUTTON_SECONDARY: return 1; // Right
            case MotionEvent.BUTTON_TERTIARY: return 2;  // Middle
            case MotionEvent.BUTTON_BACK: return 3;      // Back / X1
            case MotionEvent.BUTTON_FORWARD: return 4;   // Forward / X2
            default: return -1;
        }
    }

    private boolean handlePointerEvent(MotionEvent event) {
        final int source = event.getSource();
        final boolean isMouse = (source & InputDevice.SOURCE_MOUSE) != 0 ||
                                (source & 0x00020004) != 0 ||
                                (source & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                                (source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0;
        if (!isMouse) {
            return false;
        }
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
            float dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
            float dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            if (dx == 0 && dy == 0) {
                if (getWindow() != null && getWindow().getDecorView() != null && getWindow().getDecorView().hasPointerCapture()) {
                    dx = event.getX();
                    dy = event.getY();
                } else {
                    float x = event.getX();
                    float y = event.getY();
                    if (lastMouseX != 0 || lastMouseY != 0) {
                        dx = x - lastMouseX;
                        dy = y - lastMouseY;
                    }
                    lastMouseX = x;
                    lastMouseY = y;
                }
            }
            if (dx != 0 || dy != 0) {
                nativeOnMouseMove(dx, dy);
            }
            return true;
        } else if (action == MotionEvent.ACTION_SCROLL) {
            float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float hscroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (vscroll != 0 || hscroll != 0) {
                nativeOnMouseWheel(hscroll, vscroll);
            }
            return true;
        } else if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            int btn = event.getActionButton();
            int mapped = mapMouseButton(btn);
            if (mapped >= 0) {
                nativeOnMouseButton(mapped, action == MotionEvent.ACTION_BUTTON_PRESS);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (handlePointerEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            if (pointerCaptureRequested && getWindow() != null && getWindow().getDecorView() != null) {
                if (!getWindow().getDecorView().hasPointerCapture()) {
                    getWindow().getDecorView().requestPointerCapture();
                }
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastMouseX = event.getX();
                lastMouseY = event.getY();
                nativeOnMouseButton(0, true);
                return true;
            } else if (action == MotionEvent.ACTION_UP) {
                nativeOnMouseButton(0, false);
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_POWER ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            return super.dispatchKeyEvent(event);
        }
        if ((event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) {
            // The keyboard went away or Android lost the key-up: nothing stays held on the PC.
            nativeReleaseAllKeys();
            return super.dispatchKeyEvent(event);
        }
        // NativeActivity's input queue sees most physical keys first. Let native code map
        // them, raw scan codes included; OnPhysicalKey deduplicates events seen twice.
        if (nativeOnPhysicalKey(keyCode, event.getScanCode(), event.getMetaState(),
                                event.getAction(), event.getFlags())) {
            return true;
        }
        // Dead keys and composed accents can arrive as ACTION_MULTIPLE or with no key
        // code at all. There is no physical key to forward then, only characters.
        final int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_MULTIPLE) {
            String text = action == KeyEvent.ACTION_MULTIPLE ? event.getCharacters() : null;
            if (text == null) {
                final int unicode = event.getUnicodeChar();
                if (unicode > 0 && !Character.isISOControl(unicode)) {
                    text = new String(Character.toChars(unicode));
                }
            }
            if (text != null && !text.isEmpty()) {
                nativeOnText(text);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static native void nativeOnMouseMove(float dx, float dy);
    private static native void nativeOnMouseButton(int button, boolean down);
    private static native void nativeOnMouseWheel(float dx, float dy);
    private static native boolean nativeOnPhysicalKey(int keyCode, int scanCode, int metaState,
                                                      int action, int flags);
    private static native void nativeOnText(String text);
    private static native void nativeReleaseAllKeys();
}
