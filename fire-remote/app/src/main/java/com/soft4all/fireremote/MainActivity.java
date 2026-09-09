package com.soft4all.fireremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import dadb.Dadb;
import dadb.AdbKeyPair;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 16, 27);
    private static final int CARD = Color.rgb(18, 31, 46);
    private static final int CARD2 = Color.rgb(27, 43, 60);
    private static final int BLUE = Color.rgb(31, 124, 255);
    private static final int ORANGE = Color.rgb(255, 112, 28);
    private static final int GREEN = Color.rgb(67, 210, 134);
    private static final int MUTED = Color.rgb(166, 181, 197);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService control = Executors.newFixedThreadPool(2);
    private Dadb adb;
    private LinearLayout root;
    private TextView status;
    private EditText textInput;
    private SharedPreferences prefs;
    private volatile boolean scanning = false;
    private volatile String connectedHost = null;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        System.setProperty("user.home", getFilesDir().getAbsolutePath());
        prefs = getSharedPreferences("fire_remote", MODE_PRIVATE);
        showDiscovery();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable borderBg(int color, int stroke, float radius) {
        GradientDrawable d = bg(color, radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView txt(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(bg(color, 18));
        b.setPadding(dp(8), dp(7), dp(8), dp(7));
        b.setOnClickListener(l);
        return b;
    }

    private Button remoteButton(String s, View.OnClickListener l) {
        Button b = button(s, CARD2, l);
        b.setTextSize(s.equals("OK") ? 19 : 23);
        b.setBackground(borderBg(CARD2, Color.rgb(65, 86, 108), 100));
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        return r;
    }

    private void flex(LinearLayout r, View v, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        r.addView(v, p);
    }

    private void prepareRoot() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(BG);
        sc.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(30));
        root.setBackgroundColor(BG);
        sc.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(sc);
    }

    private void addBrand() {
        TextView title = txt("Soft4All  Fire Remote", 26, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = txt("Tu Fire TV, en tus manos", 14, ORANGE, true);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
    }

    private void section(String name) {
        TextView s = txt(name, 18, Color.WHITE, true);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(18), 0, dp(4));
        root.addView(s);
    }

    private void showDiscovery() {
        prepareRoot();
        addBrand();
        TextView h = txt("Selecciona tu Fire TV", 20, Color.WHITE, true);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dp(20), 0, dp(4));
        root.addView(h);
        status = txt("Buscando dispositivos en tu red…", 14, MUTED, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(42), dp(42));
        pp.gravity = Gravity.CENTER;
        pp.setMargins(0, dp(10), 0, dp(10));
        root.addView(pb, pp);
        LinearLayout devices = new LinearLayout(this);
        devices.setId(9001);
        devices.setOrientation(LinearLayout.VERTICAL);
        root.addView(devices);
        String last = prefs.getString("last_ip", "");
        if (!last.isEmpty()) addDeviceCard(devices, last, "Último dispositivo usado");
        Button rescan = button("↻  Buscar de nuevo", CARD2, v -> startScan());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(56));
        rp.setMargins(0, dp(14), 0, 0);
        root.addView(rescan, rp);
        TextView help = txt("La búsqueda local detecta equipos con ADB disponible. Toca el dispositivo que corresponda a tu Fire TV para enlazarlo.", 13, MUTED, false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(dp(8), dp(14), dp(8), 0);
        root.addView(help);
        startScan();
    }

    private void startScan() {
        if (scanning) return;
        LinearLayout devices = findViewById(9001);
        if (devices == null) return;
        scanning = true;
        status.setText("Buscando dispositivos en tu red…");
        io.execute(() -> {
            String myIp = localIpv4();
            if (myIp == null || !myIp.contains(".")) {
                runOnUiThread(() -> {
                    scanning = false;
                    status.setText("No pude identificar la red. Conecta el móvil a la misma Wi‑Fi que el Fire TV.");
                });
                return;
            }
            String base = myIp.substring(0, myIp.lastIndexOf('.') + 1);
            ExecutorService pool = Executors.newFixedThreadPool(48);
            AtomicInteger pending = new AtomicInteger(254);
            AtomicInteger found = new AtomicInteger(0);
            Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
            for (int i = 1; i <= 254; i++) {
                final String host = base + i;
                pool.execute(() -> {
                    try {
                        if (!host.equals(myIp) && portOpen(host, 5555, 120) && seen.add(host)) {
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
                            runOnUiThread(() -> {
                                if (found.get() == 0) status.setText("No encontré ningún dispositivo con ADB disponible.");
                                else status.setText("Búsqueda terminada · " + found.get() + " dispositivo(s)");
                            });
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
        card.setBackground(borderBg(CARD, BLUE, 18));
        TextView n = txt("▰  " + name, 16, Color.WHITE, true);
        TextView ip = txt(host, 14, Color.rgb(125, 192, 255), false);
        card.addView(n);
        card.addView(ip);
        card.setOnClickListener(v -> connectHost(host));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(6), 0, dp(6));
        devices.addView(card, cp);
    }

    private void connectHost(String host) {
        status.setText("Enlazando con " + host + "…");
        io.execute(() -> {
            try {
                if (adb != null) adb.close();
                adb = Dadb.create(host, 5555, AdbKeyPair.readDefault(), 1500, 1500, true);
                String probe = adb.shell("echo Soft4All_OK").getAllOutput();
                if (!probe.contains("Soft4All_OK")) throw new IllegalStateException("No ADB");
                connectedHost = host;
                String model = safeShell("getprop ro.product.model").trim();
                String maker = safeShell("getprop ro.product.manufacturer").trim();
                prefs.edit().putString("last_ip", host).apply();
                String label = (maker + " " + model).trim();
                if (label.isEmpty()) label = "Fire TV";
                final String finalLabel = label;
                runOnUiThread(() -> showRemote(host, finalLabel));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("No se pudo enlazar. Si la TV muestra autorización ADB, acéptala y vuelve a tocar el dispositivo."));
            }
        });
    }

    private void showRemote(String host, String deviceName) {
        prepareRoot();
        addBrand();
        LinearLayout top = row();
        TextView conn = txt("● " + deviceName + "\n" + host, 14, GREEN, true);
        flex(top, conn, 64);
        Button change = button("Cambiar TV", CARD2, v -> showDiscovery());
        flex(top, change, 64);
        root.addView(top);

        section("Navegación");
        LinearLayout up = row(); flex(up, remoteButton("▲", v -> key(19)), 72); root.addView(up);
        LinearLayout mid = row();
        flex(mid, remoteButton("◀", v -> key(21)), 72);
        flex(mid, remoteButton("OK", v -> key(23)), 72);
        flex(mid, remoteButton("▶", v -> key(22)), 72);
        root.addView(mid);
        LinearLayout down = row(); flex(down, remoteButton("▼", v -> key(20)), 72); root.addView(down);
        LinearLayout nav = row();
        flex(nav, button("↩ Atrás", CARD2, v -> key(4)), 58);
        flex(nav, button("⌂ Inicio", CARD2, v -> key(3)), 58);
        flex(nav, button("☰ Menú", CARD2, v -> key(82)), 58);
        root.addView(nav);

        section("Reproducción");
        LinearLayout media1 = row();
        flex(media1, button("⏮ Anterior", CARD2, v -> key(88)), 58);
        flex(media1, button("⏪ Retroceder", CARD2, v -> key(89)), 58);
        root.addView(media1);
        LinearLayout media2 = row();
        flex(media2, button("▶❚❚ Play / Pausa", BLUE, v -> key(85)), 58);
        flex(media2, button("■ Detener", CARD2, v -> key(86)), 58);
        root.addView(media2);
        LinearLayout media3 = row();
        flex(media3, button("⏩ Avanzar", CARD2, v -> key(90)), 58);
        flex(media3, button("⏭ Siguiente", CARD2, v -> key(87)), 58);
        root.addView(media3);

        section("Volumen");
        LinearLayout vol = row();
        flex(vol, button("Vol −", CARD2, v -> key(25)), 58);
        flex(vol, button("Silencio", CARD2, v -> key(164)), 58);
        flex(vol, button("Vol +", CARD2, v -> key(24)), 58);
        root.addView(vol);
        TextView volNote = txt("El volumen depende del modelo de Fire TV y de la configuración HDMI‑CEC/equipo de audio.", 12, MUTED, false);
        volNote.setGravity(Gravity.CENTER);
        root.addView(volNote);

        section("Sistema");
        LinearLayout sys1 = row();
        flex(sys1, button("Buscar", CARD2, v -> key(84)), 58);
        flex(sys1, button("Reproducir", CARD2, v -> key(126)), 58);
        root.addView(sys1);
        LinearLayout sys2 = row();
        flex(sys2, button("Pausa", CARD2, v -> key(127)), 58);
        flex(sys2, button("Encendido", Color.rgb(135, 42, 42), v -> key(26)), 58);
        root.addView(sys2);

        section("Teclado");
        textInput = new EditText(this);
        textInput.setSingleLine(true);
        textInput.setTextColor(Color.WHITE);
        textInput.setHintTextColor(MUTED);
        textInput.setHint("Escribe texto para el Fire TV…");
        textInput.setInputType(InputType.TYPE_CLASS_TEXT);
        textInput.setBackground(borderBg(CARD, Color.rgb(61, 82, 104), 14));
        textInput.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(56));
        tp.setMargins(dp(5), dp(5), dp(5), dp(5));
        root.addView(textInput, tp);
        LinearLayout kb = row();
        flex(kb, button("Enviar texto", BLUE, v -> sendText()), 56);
        flex(kb, button("Borrar", CARD2, v -> key(67)), 56);
        root.addView(kb);
        LinearLayout kb2 = row();
        flex(kb2, button("Enter", CARD2, v -> key(66)), 56);
        flex(kb2, button("Espacio", CARD2, v -> key(62)), 56);
        root.addView(kb2);

        section("Conexión");
        LinearLayout c = row();
        flex(c, button("Reconectar", CARD2, v -> reconnect()), 56);
        flex(c, button("Buscar otro", CARD2, v -> showDiscovery()), 56);
        root.addView(c);
        TextView note = txt("V3 · Control local por ADB. La primera vinculación puede requerir aceptar una autorización en el Fire TV.", 12, MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), dp(18), dp(8), 0);
        root.addView(note);
    }

    private void reconnect() {
        String h = connectedHost != null ? connectedHost : prefs.getString("last_ip", "");
        if (!h.isEmpty()) {
            showDiscovery();
            connectHost(h);
        }
    }

    private void key(int code) { sendCommand("input keyevent " + code); }

    private void sendText() {
        if (textInput == null) return;
        String s = textInput.getText().toString();
        if (s.isEmpty()) return;
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace(" ", "%s");
        sendCommand("input text \"" + escaped + "\"");
    }

    private void sendCommand(String cmd) {
        final Dadb current = adb;
        if (current == null) return;
        control.execute(() -> {
            try { current.shell(cmd); } catch (Exception ignored) {}
        });
    }

    private String safeShell(String cmd) throws Exception { return adb.shell(cmd).getAllOutput(); }

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
        io.shutdownNow();
        control.shutdownNow();
        try { if (adb != null) adb.close(); } catch (Exception ignored) {}
    }
}
