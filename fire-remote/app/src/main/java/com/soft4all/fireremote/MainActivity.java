package com.soft4all.fireremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
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

import dadb.AdbKeyPair;
import dadb.Dadb;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 10, 14);
    private static final int PANEL = Color.rgb(18, 21, 25);
    private static final int REMOTE = Color.rgb(24, 25, 27);
    private static final int BTN = Color.rgb(39, 41, 44);
    private static final int BTN_EDGE = Color.rgb(71, 74, 79);
    private static final int BLUE = Color.rgb(45, 125, 245);
    private static final int ORANGE = Color.rgb(255, 116, 31);
    private static final int GREEN = Color.rgb(66, 210, 132);
    private static final int MUTED = Color.rgb(165, 172, 181);
    private static final int RED = Color.rgb(172, 48, 48);

    private final ExecutorService discovery = Executors.newSingleThreadExecutor();
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Dadb adb;
    private SharedPreferences prefs;
    private LinearLayout root;
    private LinearLayout deviceList;
    private TextView status;
    private EditText keyboard;
    private volatile boolean scanning;
    private volatile String connectedHost;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
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

    private GradientDrawable roundedStroke(int color, int stroke, float radius) {
        GradientDrawable g = rounded(color, radius);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable oval(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (stroke != Color.TRANSPARENT) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button pill(String s, int color, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rounded(color, 18));
        b.setOnClickListener(action);
        return b;
    }

    private Button circle(String s, int sizeSp, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(sizeSp);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(0,0,0,0);
        b.setBackground(oval(BTN, BTN_EDGE));
        b.setOnClickListener(action);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        return r;
    }

    private void addSquare(LinearLayout r, View v, int size, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(size), dp(size));
        p.setMargins(dp(margin), dp(margin), dp(margin), dp(margin));
        r.addView(v, p);
    }

    private void addFlex(LinearLayout r, View v, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        r.addView(v, p);
    }

    private void preparePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(36));
        root.setBackgroundColor(BG);
        scroll.addView(root, new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);
    }

    private void brand() {
        TextView title = text("Soft4All  Fire Remote", 25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView sub = text("Fire TV 4K Remote", 13, ORANGE, true);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, new LinearLayout.LayoutParams(-1, dp(28)));
    }

    private void showDiscovery() {
        preparePage(); brand();
        TextView h = text("Selecciona tu Fire TV", 20, Color.WHITE, true);
        h.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(50));
        hp.setMargins(0, dp(12),0,0);
        root.addView(h,hp);

        status = text("Buscando en tu red local…", 14, MUTED, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList, new LinearLayout.LayoutParams(-1,-2));

        String last = prefs.getString("last_ip", "");
        if (!last.isEmpty()) addDevice(last, "Último Fire TV");

        Button scan = pill("↻  Buscar de nuevo", PANEL, v -> startScan());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(58));
        sp.setMargins(0, dp(14),0,0);
        root.addView(scan,sp);

        TextView note = text("Toca el Fire TV encontrado para enlazar. La detección busca dispositivos con ADB disponible en la misma Wi‑Fi.", 13, MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(10),dp(16),dp(10),0);
        root.addView(note, new LinearLayout.LayoutParams(-1,-2));
        startScan();
    }

    private void startScan() {
        if (scanning || deviceList == null) return;
        scanning = true;
        status.setText("Buscando en tu red local…");
        discovery.execute(() -> {
            String local = localIpv4();
            if (local == null || !local.contains(".")) {
                ui.post(() -> { scanning=false; status.setText("No pude identificar la Wi‑Fi local."); });
                return;
            }
            String base = local.substring(0, local.lastIndexOf('.') + 1);
            ExecutorService pool = Executors.newFixedThreadPool(64);
            AtomicInteger left = new AtomicInteger(254);
            AtomicInteger found = new AtomicInteger();
            Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
            for (int i=1;i<=254;i++) {
                final String host = base + i;
                pool.execute(() -> {
                    try {
                        if (!host.equals(local) && portOpen(host,5555,90) && seen.add(host)) {
                            int n = found.incrementAndGet();
                            ui.post(() -> { addDevice(host,"Fire TV / Android TV"); status.setText("Encontrados: " + n); });
                        }
                    } finally {
                        if (left.decrementAndGet()==0) {
                            pool.shutdown(); scanning=false;
                            ui.post(() -> status.setText(found.get()==0 ? "No encontré ningún Fire TV con ADB disponible." : "Búsqueda terminada · " + found.get() + " dispositivo(s)"));
                        }
                    }
                });
            }
        });
    }

    private void addDevice(String host, String name) {
        if (deviceList == null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(12),dp(16),dp(12));
        card.setBackground(roundedStroke(PANEL, BLUE, 18));
        TextView n = text("▰  " + name,16,Color.WHITE,true);
        TextView ip = text(host,13,Color.rgb(130,195,255),false);
        card.addView(n,new LinearLayout.LayoutParams(-1,dp(32)));
        card.addView(ip,new LinearLayout.LayoutParams(-1,dp(28)));
        card.setOnClickListener(v -> connect(host));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(6),0,dp(6));
        deviceList.addView(card,p);
    }

    private void connect(String host) {
        if (status != null) status.setText("Enlazando con " + host + "…");
        discovery.execute(() -> {
            try {
                Dadb old = adb;
                if (old != null) try { old.close(); } catch(Exception ignored) {}
                adb = Dadb.create(host,5555,AdbKeyPair.readDefault(),1000,1000,true);
                String ok = adb.shell("echo Soft4All_OK").getAllOutput();
                if (!ok.contains("Soft4All_OK")) throw new IllegalStateException();
                connectedHost = host;
                String maker = safe("getprop ro.product.manufacturer").trim();
                String model = safe("getprop ro.product.model").trim();
                String label = (maker + " " + model).trim();
                if (label.isEmpty()) label = "Fire TV";
                prefs.edit().putString("last_ip",host).apply();
                final String finalLabel = label;
                ui.post(() -> showRemote(host,finalLabel));
            } catch(Exception e) {
                ui.post(() -> { if(status!=null) status.setText("No se pudo enlazar. Acepta la autorización ADB en la TV si aparece y vuelve a tocar el dispositivo."); });
            }
        });
    }

    private void showRemote(String host, String deviceName) {
        preparePage(); brand();

        LinearLayout info = row();
        TextView connected = text("●  " + deviceName + "\n" + host,13,GREEN,true);
        connected.setPadding(dp(12),0,dp(8),0);
        addFlex(info,connected,58);
        Button change = pill("Cambiar TV",PANEL,v -> showDiscovery());
        addFlex(info,change,58);
        root.addView(info);

        LinearLayout remoteBody = new LinearLayout(this);
        remoteBody.setOrientation(LinearLayout.VERTICAL);
        remoteBody.setGravity(Gravity.CENTER_HORIZONTAL);
        remoteBody.setPadding(dp(18),dp(24),dp(18),dp(28));
        remoteBody.setBackground(roundedStroke(REMOTE,Color.rgb(52,54,58),42));
        LinearLayout.LayoutParams bodyP = new LinearLayout.LayoutParams(-1,-2);
        bodyP.setMargins(dp(14),dp(14),dp(14),dp(12));
        root.addView(remoteBody,bodyP);

        LinearLayout top = row();
        Button power = circle("⏻",21,v -> key(26));
        Button search = circle("⌕",23,v -> key(84));
        addSquare(top,power,54,24);
        addSquare(top,search,54,24);
        remoteBody.addView(top);

        Space gap1 = new Space(this);
        remoteBody.addView(gap1,new LinearLayout.LayoutParams(1,dp(14)));

        FrameLayout pad = new FrameLayout(this);
        pad.setBackground(oval(Color.rgb(31,33,36),Color.rgb(78,81,86)));
        LinearLayout.LayoutParams padP = new LinearLayout.LayoutParams(dp(262),dp(262));
        padP.gravity=Gravity.CENTER;
        remoteBody.addView(pad,padP);

        Button up = circle("▲",24,v -> key(19));
        Button down = circle("▼",24,v -> key(20));
        Button left = circle("◀",24,v -> key(21));
        Button right = circle("▶",24,v -> key(22));
        Button ok = circle("OK",20,v -> key(23));
        ok.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        place(pad,up,86,64,88,4);
        place(pad,down,86,64,88,194);
        place(pad,left,64,86,4,88);
        place(pad,right,64,86,194,88);
        place(pad,ok,112,112,75,75);
        enableRepeat(up,19); enableRepeat(down,20); enableRepeat(left,21); enableRepeat(right,22);

        Space gap2 = new Space(this);
        remoteBody.addView(gap2,new LinearLayout.LayoutParams(1,dp(18)));

        LinearLayout nav = row();
        Button back = circle("↶",24,v -> key(4));
        Button home = circle("⌂",23,v -> key(3));
        Button menu = circle("≡",26,v -> key(82));
        addSquare(nav,back,60,8); addSquare(nav,home,60,8); addSquare(nav,menu,60,8);
        remoteBody.addView(nav);

        LinearLayout media = row();
        Button rw = circle("◀◀",15,v -> key(89));
        Button pp = circle("▶❚❚",14,v -> key(85));
        Button ff = circle("▶▶",15,v -> key(90));
        addSquare(media,rw,60,8); addSquare(media,pp,60,8); addSquare(media,ff,60,8);
        remoteBody.addView(media);

        LinearLayout lower = row();
        Button mute = circle("🔇",18,v -> key(164));
        LinearLayout volume = new LinearLayout(this);
        volume.setOrientation(LinearLayout.VERTICAL);
        volume.setGravity(Gravity.CENTER);
        volume.setBackground(roundedStroke(BTN,BTN_EDGE,26));
        Button volUp = pill("+",Color.TRANSPARENT,v -> key(24));
        volUp.setTextSize(24); volUp.setBackgroundColor(Color.TRANSPARENT);
        Button volDown = pill("−",Color.TRANSPARENT,v -> key(25));
        volDown.setTextSize(27); volDown.setBackgroundColor(Color.TRANSPARENT);
        volume.addView(volUp,new LinearLayout.LayoutParams(dp(58),dp(54)));
        volume.addView(volDown,new LinearLayout.LayoutParams(dp(58),dp(54)));
        enableRepeat(volUp,24); enableRepeat(volDown,25);
        addSquare(lower,mute,60,20);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(dp(60),dp(112)); vp.setMargins(dp(20),dp(8),dp(20),dp(8)); lower.addView(volume,vp);
        remoteBody.addView(lower);

        TextView logo = text("fire tv",22,Color.rgb(190,193,198),true);
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0,dp(14),0,0);
        remoteBody.addView(logo,new LinearLayout.LayoutParams(-1,dp(50)));

        TextView more = text("Más controles",18,Color.WHITE,true);
        more.setGravity(Gravity.CENTER);
        root.addView(more,new LinearLayout.LayoutParams(-1,dp(46)));

        LinearLayout extras1 = row();
        addFlex(extras1,pill("⏮  Anterior",PANEL,v -> key(88)),54);
        addFlex(extras1,pill("⏭  Siguiente",PANEL,v -> key(87)),54);
        root.addView(extras1);
        LinearLayout extras2 = row();
        addFlex(extras2,pill("■  Detener",PANEL,v -> key(86)),54);
        addFlex(extras2,pill("↵  Enter",PANEL,v -> key(66)),54);
        root.addView(extras2);

        TextView kh = text("Teclado",18,Color.WHITE,true); kh.setGravity(Gravity.CENTER);
        root.addView(kh,new LinearLayout.LayoutParams(-1,dp(46)));
        keyboard = new EditText(this);
        keyboard.setSingleLine(true);
        keyboard.setTextColor(Color.WHITE);
        keyboard.setHintTextColor(MUTED);
        keyboard.setHint("Escribe en el Fire TV…");
        keyboard.setInputType(InputType.TYPE_CLASS_TEXT);
        keyboard.setBackground(roundedStroke(PANEL,Color.rgb(68,72,78),16));
        keyboard.setPadding(dp(14),0,dp(14),0);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1,dp(56)); kp.setMargins(dp(5),0,dp(5),dp(6)); root.addView(keyboard,kp);
        LinearLayout kbr = row();
        addFlex(kbr,pill("Enviar texto",BLUE,v -> sendText()),54);
        addFlex(kbr,pill("⌫  Borrar",PANEL,v -> key(67)),54);
        root.addView(kbr);

        LinearLayout conn = row();
        addFlex(conn,pill("Reconectar",PANEL,v -> reconnect()),54);
        addFlex(conn,pill("Buscar otro",PANEL,v -> showDiscovery()),54);
        root.addView(conn);

        TextView note = text("V4 · Interfaz tipo Alexa Voice Remote. El botón de búsqueda abre la búsqueda de Fire TV; no puede captar voz como el micrófono físico. Encendido y volumen dependen también del modelo y HDMI‑CEC.",12,MUTED,false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(12),dp(14),dp(12),0);
        root.addView(note,new LinearLayout.LayoutParams(-1,-2));
    }

    private void place(FrameLayout parent, View v, int w, int h, int left, int top) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(w),dp(h));
        p.leftMargin=dp(left); p.topMargin=dp(top); parent.addView(v,p);
    }

    private void enableRepeat(View v, int code) {
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] repeater = new Runnable[1];
        repeater[0] = new Runnable() { @Override public void run() { key(code); h.postDelayed(this,180); } };
        v.setOnTouchListener((view,event) -> {
            if (event.getAction()==MotionEvent.ACTION_DOWN) {
                key(code); h.postDelayed(repeater[0],380); return true;
            }
            if (event.getAction()==MotionEvent.ACTION_UP || event.getAction()==MotionEvent.ACTION_CANCEL) {
                h.removeCallbacks(repeater[0]); view.performClick(); return true;
            }
            return false;
        });
    }

    private void key(int code) {
        Dadb d = adb;
        if (d == null) return;
        control.execute(() -> {
            try { d.shell("input keyevent " + code); }
            catch(Exception e) { ui.post(() -> Toast.makeText(this,"Se perdió la conexión",Toast.LENGTH_SHORT).show()); }
        });
    }

    private void sendText() {
        if (keyboard == null) return;
        String s = keyboard.getText().toString();
        if (s.isEmpty()) return;
        String safe = s.replace("\\","\\\\").replace("'","\\'").replace(" ","%s");
        Dadb d = adb;
        if (d == null) return;
        control.execute(() -> { try { d.shell("input text '" + safe + "'"); } catch(Exception ignored) {} });
    }

    private void reconnect() {
        String h = connectedHost != null ? connectedHost : prefs.getString("last_ip","");
        if (!h.isEmpty()) connect(h);
    }

    private String safe(String cmd) throws Exception { return adb.shell(cmd).getAllOutput(); }

    private boolean portOpen(String host,int port,int timeout) {
        try(Socket s=new Socket()) { s.connect(new InetSocketAddress(host,port),timeout); return true; }
        catch(Exception e) { return false; }
    }

    private String localIpv4() {
        try {
            for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!ni.isUp() || ni.isLoopback()) continue;
                for(java.net.InetAddress a:Collections.list(ni.getInetAddresses())) {
                    if(a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch(Exception ignored) {}
        return null;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        scanning=false;
        discovery.shutdownNow(); control.shutdownNow();
        try { if(adb!=null) adb.close(); } catch(Exception ignored) {}
    }
}
