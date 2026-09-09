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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dadb.Dadb;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 18, 29);
    private static final int CARD = Color.rgb(18, 34, 50);
    private static final int CARD2 = Color.rgb(29, 48, 67);
    private static final int BLUE = Color.rgb(20, 120, 245);
    private static final int ORANGE = Color.rgb(255, 110, 25);
    private static final int MUTED = Color.rgb(165, 180, 195);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Dadb adb;
    private LinearLayout root;
    private TextView status;
    private EditText textInput;
    private SharedPreferences prefs;
    private volatile boolean scanning = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        System.setProperty("user.home", getFilesDir().getAbsolutePath());
        prefs = getSharedPreferences("fire_remote", MODE_PRIVATE);
        showDiscovery();
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable borderBg(int color, int stroke, float radius) {
        GradientDrawable d = bg(color, radius); d.setStroke(dp(1), stroke); return d;
    }

    private TextView txt(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(16);
        b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setBackground(bg(color, 18));
        b.setOnClickListener(l); b.setPadding(dp(8), dp(8), dp(8), dp(8)); return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER); return r;
    }

    private void flex(LinearLayout r, View v, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1); p.setMargins(dp(5), dp(5), dp(5), dp(5)); r.addView(v, p);
    }

    private void prepareRoot() {
        ScrollView sc = new ScrollView(this); sc.setFillViewport(true); sc.setBackgroundColor(BG);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(18), dp(16), dp(28)); root.setBackgroundColor(BG);
        sc.addView(root); setContentView(sc);
    }

    private void addBrand() {
        TextView title = txt("Soft4All  Fire Remote", 26, Color.WHITE, true); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = txt("Tu Fire TV, en tus manos", 14, ORANGE, true); sub.setGravity(Gravity.CENTER); root.addView(sub);
    }

    private void showDiscovery() {
        prepareRoot(); addBrand();
        TextView h = txt("Selecciona tu Fire TV", 20, Color.WHITE, true); h.setGravity(Gravity.CENTER); h.setPadding(0, dp(22), 0, dp(4)); root.addView(h);
        status = txt("Buscando Fire TV en tu red Wi‑Fi…", 15, MUTED, false); status.setGravity(Gravity.CENTER); root.addView(status);

        ProgressBar pb = new ProgressBar(this); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(48), dp(48)); pp.gravity = Gravity.CENTER; pp.setMargins(0, dp(12), 0, dp(12)); root.addView(pb, pp);

        LinearLayout devices = new LinearLayout(this); devices.setId(9001); devices.setOrientation(LinearLayout.VERTICAL); root.addView(devices);

        Button rescan = button("↻  Buscar de nuevo", CARD2, v -> startScan());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(58)); rp.setMargins(0, dp(16), 0, 0); root.addView(rescan, rp);

        TextView help = txt("La app detecta automáticamente equipos con control ADB disponible en la misma red. Toca un dispositivo para enlazar.", 13, MUTED, false);
        help.setGravity(Gravity.CENTER); help.setPadding(dp(6), dp(18), dp(6), dp(8)); root.addView(help);

        String last = prefs.getString("last_ip", "");
        if (!last.isEmpty()) addDeviceCard(devices, last, "Último Fire TV usado");
        startScan();
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        LinearLayout devices = findViewById(9001);
        if (devices == null) return;
        devices.removeAllViews();
        status.setText("Buscando Fire TV en tu red Wi‑Fi…");

        io.execute(() -> {
            String myIp = localIpv4();
            if (myIp == null || !myIp.contains(".")) {
                runOnUiThread(() -> { scanning = false; status.setText("No pude identificar la red local. Conecta el móvil a la misma Wi‑Fi que el Fire TV."); });
                return;
            }
            String base = myIp.substring(0, myIp.lastIndexOf('.') + 1);
            ExecutorService pool = Executors.newFixedThreadPool(32);
            AtomicInteger pending = new AtomicInteger(254);
            AtomicInteger found = new AtomicInteger(0);
            Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
            for (int i = 1; i <= 254; i++) {
                final String host = base + i;
                pool.execute(() -> {
                    try {
                        if (!host.equals(myIp) && portOpen(host, 5555, 180) && seen.add(host)) {
                            int n = found.incrementAndGet();
                            runOnUiThread(() -> {
                                addDeviceCard(devices, host, "Fire TV / Android TV detectado");
                                status.setText("Dispositivos encontrados: " + n);
                            });
                        }
                    } finally {
                        if (pending.decrementAndGet() == 0) {
                            pool.shutdown(); scanning = false;
                            runOnUiThread(() -> {
                                if (found.get() == 0) status.setText("No encontré ningún Fire TV con ADB disponible.");
                                else status.setText("Búsqueda terminada · " + found.get() + " dispositivo(s)");
                            });
                        }
                    }
                });
            }
        });
    }

    private void addDeviceCard(LinearLayout devices, String host, String name) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14), dp(10), dp(14), dp(10)); card.setBackground(borderBg(CARD, BLUE, 18));
        TextView n = txt("▰  " + name, 16, Color.WHITE, true); TextView ip = txt(host, 14, Color.rgb(120,190,255), false);
        card.addView(n); card.addView(ip); card.setOnClickListener(v -> connectHost(host));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.setMargins(0, dp(6), 0, dp(6)); devices.addView(card, cp);
    }

    private void connectHost(String host) {
        status.setText("Enlazando con " + host + "…");
        io.execute(() -> {
            try {
                if (adb != null) adb.close();
                adb = Dadb.create(host, 5555);
                String model = safeShell("getprop ro.product.model").trim();
                String maker = safeShell("getprop ro.product.manufacturer").trim();
                safeShell("echo Soft4All_OK");
                prefs.edit().putString("last_ip", host).apply();
                final String label = ((maker + " " + model).trim().isEmpty()) ? "Fire TV" : (maker + " " + model).trim();
                runOnUiThread(() -> showRemote(host, label));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("No se pudo enlazar. Si aparece una autorización ADB en la TV, acéptala y toca de nuevo el dispositivo."));
            }
        });
    }

    private void showRemote(String host, String deviceName) {
        prepareRoot(); addBrand();
        LinearLayout top = row();
        TextView conn = txt("● Conectado: " + deviceName + "\n" + host, 14, Color.rgb(80,220,140), true); flex(top, conn, 62);
        Button change = button("Cambiar", CARD2, v -> showDiscovery()); flex(top, change, 62); root.addView(top);

        TextView section = txt("Mando", 18, Color.WHITE, true); section.setGravity(Gravity.CENTER); root.addView(section);

        LinearLayout up = row(); flex(up, roundButton("▲", v -> key(19)), 72); root.addView(up);
        LinearLayout mid = row(); flex(mid, roundButton("◀", v -> key(21)), 72); flex(mid, roundButton("OK", v -> key(23)), 72); flex(mid, roundButton("▶", v -> key(22)), 72); root.addView(mid);
        LinearLayout down = row(); flex(down, roundButton("▼", v -> key(20)), 72); root.addView(down);

        LinearLayout nav = row(); flex(nav, button("↩  Atrás", CARD2, v -> key(4)), 58); flex(nav, button("⌂  Inicio", CARD2, v -> key(3)), 58); flex(nav, button("☰  Menú", CARD2, v -> key(82)), 58); root.addView(nav);
        LinearLayout media = row(); flex(media, button("⏪", CARD2, v -> key(89)), 58); flex(media, button("▶❚❚", BLUE, v -> key(85)), 58); flex(media, button("⏩", CARD2, v -> key(90)), 58); root.addView(media);
        LinearLayout vol = row(); flex(vol, button("Vol −", CARD2, v -> key(25)), 58); flex(vol, button("Silencio", CARD2, v -> key(164)), 58); flex(vol, button("Vol +", CARD2, v -> key(24)), 58); root.addView(vol);

        TextView apps = txt("Accesos rápidos", 17, Color.WHITE, true); apps.setGravity(Gravity.CENTER); apps.setPadding(0, dp(14), 0, dp(4)); root.addView(apps);
        LinearLayout ar = row();
        flex(ar, button("Prime Video", Color.rgb(20,90,160), v -> launch("com.amazon.avod.thirdpartyclient")), 56);
        flex(ar, button("Netflix", Color.rgb(150,20,25), v -> launch("com.netflix.ninja")), 56);
        flex(ar, button("YouTube", Color.rgb(180,25,25), v -> launch("com.amazon.firetv.youtube")), 56); root.addView(ar);

        TextView keyboard = txt("Teclado", 17, Color.WHITE, true); keyboard.setGravity(Gravity.CENTER); keyboard.setPadding(0, dp(14), 0, dp(4)); root.addView(keyboard);
        textInput = new EditText(this); textInput.setSingleLine(true); textInput.setTextColor(Color.WHITE); textInput.setHintTextColor(MUTED); textInput.setHint("Escribe en tu Fire TV…"); textInput.setInputType(InputType.TYPE_CLASS_TEXT); textInput.setBackground(borderBg(CARD, Color.rgb(60,80,100), 14)); textInput.setPadding(dp(14),0,dp(14),0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(56)); tp.setMargins(dp(5), dp(5), dp(5), dp(5)); root.addView(textInput, tp);
        root.addView(button("Enviar texto", BLUE, v -> sendText()), new LinearLayout.LayoutParams(-1, dp(56)));

        TextView note = txt("Control local por ADB. La primera vinculación puede requerir aceptar la autorización que aparece en el Fire TV.", 12, MUTED, false); note.setGravity(Gravity.CENTER); note.setPadding(dp(8),dp(18),dp(8),0); root.addView(note);
    }

    private Button roundButton(String s, View.OnClickListener l) {
        Button b = button(s, CARD2, l); b.setTextSize(s.equals("OK") ? 19 : 24); b.setBackground(borderBg(CARD2, Color.rgb(65,85,105), 100)); return b;
    }

    private void key(int code) { shell("input keyevent " + code); }
    private void launch(String pkg) { shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"); }

    private void sendText() {
        if (textInput == null) return; String s = textInput.getText().toString();
        if (!s.isEmpty()) shell("input text '" + s.replace("'", "\\'").replace(" ", "%s") + "'");
    }

    private void shell(String cmd) {
        if (adb == null) return;
        io.execute(() -> { try { adb.shell(cmd); } catch (Exception ignored) {} });
    }

    private String safeShell(String cmd) throws Exception { return adb.shell(cmd).getAllOutput(); }

    private boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), timeoutMs); return true; } catch (Exception e) { return false; }
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

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        super.onDestroy(); scanning = false; io.shutdownNow(); try { if (adb != null) adb.close(); } catch (Exception ignored) {}
    }
}
