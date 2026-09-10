package com.soft4all.fireremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dadb.AdbKeyPair;
import dadb.Dadb;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 14, 23);
    private static final int REMOTE = Color.rgb(16, 22, 29);
    private static final int BUTTON = Color.rgb(38, 47, 57);
    private static final int BLUE = Color.rgb(24, 119, 246);
    private static final int ORANGE = Color.rgb(255, 112, 28);
    private static final int GREEN = Color.rgb(57, 211, 132);
    private static final int MUTED = Color.rgb(165, 178, 192);
    private static final int VOICE_REQ = 5105;

    private final ExecutorService discovery = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor control = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Dadb adb;
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status;
    private EditText textInput;
    private volatile boolean scanning;
    private volatile String connectedHost;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        System.setProperty("user.home", getFilesDir().getAbsolutePath());
        prefs = getSharedPreferences("fire_remote", MODE_PRIVATE);
        showDiscovery();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private GradientDrawable outlined(int color, int stroke, float radius) {
        GradientDrawable g = rounded(color, radius);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(5), dp(5), dp(5), dp(5));
        b.setBackground(rounded(color, 100));
        b.setSoundEffectsEnabled(false);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        return r;
    }

    private void flex(LinearLayout r, View v, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(height), 1f);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        r.addView(v, p);
    }

    private void prepareRoot() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(30));
        root.setBackgroundColor(BG);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void addBrand() {
        TextView title = text("Soft4All  Fire Remote", 25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = text("Fire TV 4K Remote · V5", 13, ORANGE, true);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
    }

    private void showDiscovery() {
        prepareRoot();
        addBrand();
        TextView h = text("Selecciona tu Fire TV", 20, Color.WHITE, true);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dp(18), 0, dp(4));
        root.addView(h);
        status = text("Buscando dispositivos en tu red…", 14, MUTED, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(42), dp(42));
        pp.gravity = Gravity.CENTER;
        pp.setMargins(0, dp(8), 0, dp(8));
        root.addView(pb, pp);

        LinearLayout devices = new LinearLayout(this);
        devices.setId(9001);
        devices.setOrientation(LinearLayout.VERTICAL);
        root.addView(devices);

        String last = prefs.getString("last_ip", "");
        if (!last.isEmpty()) addDeviceCard(devices, last, "Último Fire TV usado");

        Button again = button("↻  Buscar de nuevo", BUTTON);
        again.setOnClickListener(v -> startScan());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(56));
        ap.setMargins(0, dp(12), 0, 0);
        root.addView(again, ap);
        startScan();
    }

    private void startScan() {
        if (scanning) return;
        LinearLayout devices = findViewById(9001);
        if (devices == null) return;
        scanning = true;
        status.setText("Buscando dispositivos en tu red…");

        discovery.execute(() -> {
            String myIp = localIpv4();
            if (myIp == null || !myIp.contains(".")) {
                runOnUiThread(() -> {
                    scanning = false;
                    status.setText("No pude identificar la red local.");
                });
                return;
            }
            String base = myIp.substring(0, myIp.lastIndexOf('.') + 1);
            ExecutorService pool = Executors.newFixedThreadPool(64);
            AtomicInteger pending = new AtomicInteger(254);
            AtomicInteger found = new AtomicInteger();
            Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());

            for (int i = 1; i <= 254; i++) {
                final String host = base + i;
                pool.execute(() -> {
                    try {
                        if (!host.equals(myIp) && portOpen(host, 5555, 90) && seen.add(host)) {
                            int n = found.incrementAndGet();
                            runOnUiThread(() -> {
                                addDeviceCard(devices, host, "Fire TV / Android detectado");
                                status.setText("Encontrados: " + n);
                            });
                        }
                    } finally {
                        if (pending.decrementAndGet() == 0) {
                            pool.shutdown();
                            scanning = false;
                            runOnUiThread(() -> status.setText(found.get() == 0
                                    ? "No encontré ningún Fire TV con ADB disponible."
                                    : "Búsqueda terminada · " + found.get() + " dispositivo(s)"));
                        }
                    }
                });
            }
        });
    }

    private void addDeviceCard(LinearLayout devices, String host, String name) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(outlined(Color.rgb(18, 31, 45), BLUE, 18));
        card.addView(text("▰  " + name, 16, Color.WHITE, true));
        card.addView(text(host, 14, Color.rgb(128, 194, 255), false));
        card.setOnClickListener(v -> connectHost(host));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        devices.addView(card, p);
    }

    private void connectHost(String host) {
        if (status != null) status.setText("Enlazando con " + host + "…");
        discovery.execute(() -> {
            try {
                if (adb != null) adb.close();
                adb = Dadb.create(host, 5555, AdbKeyPair.readDefault(), 1200, 1200, true);
                String probe = adb.shell("echo Soft4All_OK").getAllOutput();
                if (!probe.contains("Soft4All_OK")) throw new Exception("ADB probe failed");
                connectedHost = host;
                String maker = adb.shell("getprop ro.product.manufacturer").getAllOutput().trim();
                String model = adb.shell("getprop ro.product.model").getAllOutput().trim();
                prefs.edit().putString("last_ip", host).apply();
                final String device = (maker + " " + model).trim().isEmpty() ? "Fire TV 4K" : (maker + " " + model).trim();
                runOnUiThread(() -> showRemote(host, device));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (status != null) status.setText("No se pudo enlazar. Acepta la autorización ADB en el Fire TV y vuelve a tocarlo.");
                });
            }
        });
    }

    private void showRemote(String host, String deviceName) {
        prepareRoot();
        addBrand();

        LinearLayout info = row();
        TextView connected = text("● " + deviceName + "\n" + host, 13, GREEN, true);
        flex(info, connected, 58);
        Button change = button("Cambiar TV", BUTTON);
        change.setOnClickListener(v -> showDiscovery());
        flex(info, change, 58);
        root.addView(info);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(16), dp(14), dp(18));
        body.setBackground(outlined(REMOTE, Color.rgb(48, 58, 69), 34));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(10), 0, dp(10));
        root.addView(body, bp);

        LinearLayout top = row();
        Button power = button("⏻", Color.rgb(74, 31, 31)); power.setTextSize(22); power.setOnClickListener(v -> key(26));
        Button voice = button("🎙", BLUE); voice.setTextSize(21); voice.setOnClickListener(v -> startVoice());
        Button search = button("⌕", BUTTON); search.setTextSize(23); search.setOnClickListener(v -> key(84));
        flex(top, power, 58); flex(top, voice, 58); flex(top, search, 58); body.addView(top);

        TextView hint = text("Mantén pulsada la cruceta o el volumen para repetir", 11, MUTED, false);
        hint.setGravity(Gravity.CENTER); body.addView(hint);

        LinearLayout up = row();
        Button bUp = remoteKey("▲", 19); flex(up, bUp, 76); body.addView(up);
        LinearLayout mid = row();
        Button bLeft = remoteKey("◀", 21); Button ok = remoteKey("OK", 23); ok.setTextSize(19); Button bRight = remoteKey("▶", 22);
        flex(mid, bLeft, 76); flex(mid, ok, 76); flex(mid, bRight, 76); body.addView(mid);
        LinearLayout down = row(); Button bDown = remoteKey("▼", 20); flex(down, bDown, 76); body.addView(down);

        LinearLayout nav = row();
        Button back = button("↩", BUTTON); back.setTextSize(22); back.setOnClickListener(v -> key(4));
        Button home = button("⌂", BUTTON); home.setTextSize(23); home.setOnClickListener(v -> key(3));
        Button menu = button("☰", BUTTON); menu.setTextSize(22); menu.setOnClickListener(v -> key(82));
        flex(nav, back, 58); flex(nav, home, 58); flex(nav, menu, 58); body.addView(nav);

        LinearLayout media = row();
        Button rw = button("⏪", BUTTON); rw.setOnClickListener(v -> key(89));
        Button pp = button("▶❚❚", BUTTON); pp.setOnClickListener(v -> key(85));
        Button ff = button("⏩", BUTTON); ff.setOnClickListener(v -> key(90));
        flex(media, rw, 58); flex(media, pp, 58); flex(media, ff, 58); body.addView(media);

        LinearLayout volume = row();
        Button mute = remoteKey("🔇", 164);
        Button volDown = remoteKey("−", 25); volDown.setTextSize(25);
        Button volUp = remoteKey("+", 24); volUp.setTextSize(25);
        flex(volume, mute, 58); flex(volume, volDown, 58); flex(volume, volUp, 58); body.addView(volume);

        TextView appsTitle = text("Accesos directos", 15, Color.WHITE, true); appsTitle.setGravity(Gravity.CENTER); appsTitle.setPadding(0, dp(14),0,dp(4)); body.addView(appsTitle);
        LinearLayout apps1 = row();
        flex(apps1, appButton("Prime", () -> launchCandidates("prime", new String[]{"com.amazon.cloud9","com.amazon.avod.thirdpartyclient","com.amazon.amazonvideo.livingroom"})), 54);
        flex(apps1, appButton("Netflix", () -> launchCandidates("netflix", new String[]{"com.netflix.ninja"})), 54);
        flex(apps1, appButton("Disney+", () -> launchCandidates("disney", new String[]{"com.disney.disneyplus"})), 54);
        body.addView(apps1);
        LinearLayout apps2 = row();
        flex(apps2, appButton("Max", () -> launchCandidates("max", new String[]{"com.wbd.stream","com.hbo.hbonow","com.discovery.discoplus"})), 54);
        Button custom1 = customButton(1); Button custom2 = customButton(2);
        flex(apps2, custom1, 54); flex(apps2, custom2, 54); body.addView(apps2);

        TextView configHint = text("Mantén pulsado APP 1 o APP 2 para cambiar su aplicación", 11, MUTED, false);
        configHint.setGravity(Gravity.CENTER); body.addView(configHint);

        TextView extras = text("Más controles", 16, Color.WHITE, true); extras.setGravity(Gravity.CENTER); extras.setPadding(0,dp(12),0,dp(4)); root.addView(extras);

        LinearLayout extra1 = row();
        Button prev = button("⏮ Anterior", BUTTON); prev.setOnClickListener(v -> key(88));
        Button next = button("Siguiente ⏭", BUTTON); next.setOnClickListener(v -> key(87));
        flex(extra1, prev, 54); flex(extra1, next, 54); root.addView(extra1);

        LinearLayout extra2 = row();
        Button play = button("▶ Play", BUTTON); play.setOnClickListener(v -> key(126));
        Button pause = button("❚❚ Pausa", BUTTON); pause.setOnClickListener(v -> key(127));
        flex(extra2, play, 54); flex(extra2, pause, 54); root.addView(extra2);

        textInput = new EditText(this);
        textInput.setSingleLine(true);
        textInput.setTextColor(Color.WHITE);
        textInput.setHintTextColor(MUTED);
        textInput.setHint("Escribe en el Fire TV…");
        textInput.setInputType(InputType.TYPE_CLASS_TEXT);
        textInput.setBackground(outlined(Color.rgb(18,31,45), Color.rgb(62,82,104), 14));
        textInput.setPadding(dp(14),0,dp(14),0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(54));
        tp.setMargins(dp(5),dp(6),dp(5),dp(5)); root.addView(textInput,tp);

        LinearLayout keyboard = row();
        Button send = button("Enviar texto", BLUE); send.setOnClickListener(v -> sendText());
        Button del = button("⌫", BUTTON); del.setOnClickListener(v -> key(67));
        Button enter = button("Enter", BUTTON); enter.setOnClickListener(v -> key(66));
        flex(keyboard, send,54); flex(keyboard,del,54); flex(keyboard,enter,54); root.addView(keyboard);

        TextView note = text("V5 · El botón de voz usa el reconocimiento del móvil y envía el texto al buscador del Fire TV. ADB debe estar autorizado.", 11, MUTED, false);
        note.setGravity(Gravity.CENTER); note.setPadding(dp(8),dp(14),dp(8),0); root.addView(note);
    }

    private Button remoteKey(String label, int keyCode) {
        Button b = button(label, BUTTON);
        b.setTextSize(23);
        b.setBackground(outlined(BUTTON, Color.rgb(67,79,91), 100));
        bindRepeat(b, keyCode);
        return b;
    }

    private void bindRepeat(Button b, int keyCode) {
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] repeater = new Runnable[1];
        repeater[0] = () -> {
            key(keyCode);
            h.postDelayed(repeater[0], 115);
        };
        b.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                key(keyCode);
                h.postDelayed(repeater[0], 360);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                h.removeCallbacks(repeater[0]);
                return true;
            }
            return true;
        });
    }

    private Button appButton(String label, Runnable action) {
        Button b = button(label, Color.rgb(28, 68, 112));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button customButton(int slot) {
        String label = prefs.getString("custom" + slot + "_label", "APP " + slot);
        String pkg = prefs.getString("custom" + slot + "_pkg", "");
        Button b = appButton(label, () -> {
            String p = prefs.getString("custom" + slot + "_pkg", "");
            if (p.isEmpty()) configureCustom(slot, b);
            else launchCandidates("custom" + slot, new String[]{p});
        });
        b.setOnLongClickListener(v -> { configureCustom(slot, b); return true; });
        if (pkg.isEmpty()) b.setText("+ APP " + slot);
        return b;
    }

    private void configureCustom(int slot, Button target) {
        final String[] labels = {"YouTube", "Spotify", "Plex", "Apple TV+", "DAZN", "RTVE Play", "Paquete personalizado"};
        final String[] pkgs = {"com.amazon.firetv.youtube", "com.spotify.tv.android", "com.plexapp.android", "com.apple.atve.amazon.appletv", "com.dazn", "com.rtve.play", ""};
        new AlertDialog.Builder(this)
                .setTitle("Configurar APP " + slot)
                .setItems(labels, (d, which) -> {
                    if (which == labels.length - 1) showCustomPackageDialog(slot, target);
                    else saveCustom(slot, labels[which], pkgs[which], target);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showCustomPackageDialog(int slot, Button target) {
        EditText input = new EditText(this);
        input.setHint("com.ejemplo.app");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Paquete de la aplicación")
                .setView(input)
                .setPositiveButton("Guardar", (d,w) -> {
                    String p = input.getText().toString().trim();
                    if (!p.isEmpty()) saveCustom(slot, "APP " + slot, p, target);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void saveCustom(int slot, String label, String pkg, Button target) {
        prefs.edit().putString("custom" + slot + "_label", label).putString("custom" + slot + "_pkg", pkg).apply();
        target.setText(label);
    }

    private void launchCandidates(String cacheKey, String[] candidates) {
        if (adb == null) return;
        control.execute(() -> {
            try {
                String cached = prefs.getString("resolved_" + cacheKey, "");
                if (!cached.isEmpty() && packageExists(cached)) {
                    adb.shell("monkey -p " + cached + " -c android.intent.category.LAUNCHER 1");
                    return;
                }
                for (String p : candidates) {
                    if (packageExists(p)) {
                        prefs.edit().putString("resolved_" + cacheKey, p).apply();
                        adb.shell("monkey -p " + p + " -c android.intent.category.LAUNCHER 1");
                        return;
                    }
                }
                runOnUiThread(() -> Toast.makeText(this, "Aplicación no encontrada en este Fire TV", Toast.LENGTH_SHORT).show());
            } catch (Exception ignored) {}
        });
    }

    private boolean packageExists(String pkg) throws Exception {
        return adb.shell("pm path " + pkg).getAllOutput().contains("package:");
    }

    private void startVoice() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla");
            startActivityForResult(i, VOICE_REQ);
        } catch (Exception e) {
            Toast.makeText(this, "El reconocimiento de voz no está disponible en este móvil", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                final String spoken = results.get(0);
                key(84);
                ui.postDelayed(() -> sendTextValue(spoken), 180);
            }
        }
    }

    private void sendText() {
        if (textInput != null) sendTextValue(textInput.getText().toString());
    }

    private void sendTextValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String escaped = raw.trim().replace("\\", "\\\\").replace("'", "\\'").replace(" ", "%s");
        sendCommand("input text '" + escaped + "'");
    }

    private void key(int code) { sendCommand("input keyevent " + code); }

    private void sendCommand(String command) {
        if (adb == null) return;
        control.execute(() -> {
            try { adb.shell(command); }
            catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Conexión perdida", Toast.LENGTH_SHORT).show()); }
        });
    }

    private boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) { return false; }
    }

    private String localIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        scanning = false;
        discovery.shutdownNow();
        control.shutdownNow();
        try { if (adb != null) adb.close(); } catch (Exception ignored) {}
    }
}
