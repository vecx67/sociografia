package com.soft4all.fireremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends Activity {
    private static final String API_KEY = "0987654321";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MIC_REQ = 6106;

    private static final int BG = Color.rgb(7,14,23);
    private static final int PANEL = Color.rgb(16,22,29);
    private static final int BTN = Color.rgb(38,47,57);
    private static final int BLUE = Color.rgb(24,119,246);
    private static final int ORANGE = Color.rgb(255,112,28);
    private static final int GREEN = Color.rgb(57,211,132);
    private static final int MUTED = Color.rgb(165,178,192);

    private final ExecutorService discovery = Executors.newSingleThreadExecutor();
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final ExecutorService audioExec = Executors.newSingleThreadExecutor();

    private OkHttpClient http;
    private LinearLayout root;
    private TextView status;
    private EditText textInput;
    private android.content.SharedPreferences prefs;
    private String host;
    private String token;
    private volatile boolean scanning;
    private volatile boolean voiceActive;
    private AudioRecord recorder;
    private WebSocket voiceSocket;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("fire_remote_v6", MODE_PRIVATE);
        http = unsafeClient();
        showDiscovery();
    }

    private OkHttpClient unsafeClient() {
        try {
            final X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] x, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] x, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());
            SSLSocketFactory sf = sc.getSocketFactory();
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sf, tm)
                    .hostnameVerifier((h,s) -> true)
                    .connectTimeout(900, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, float radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g;
    }
    private GradientDrawable outline(int color, int stroke, float radius) {
        GradientDrawable g = shape(color, radius); g.setStroke(dp(1), stroke); return g;
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(10),dp(7),dp(10),dp(7)); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private Button button(String s, int color) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setBackground(shape(color, 100)); b.setSoundEffectsEnabled(false); return b;
    }
    private LinearLayout row() { LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER); return r; }
    private void flex(LinearLayout r, View v, int h) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(h),1); p.setMargins(dp(5),dp(5),dp(5),dp(5)); r.addView(v,p); }
    private void prepareRoot() {
        ScrollView s = new ScrollView(this); s.setFillViewport(true); s.setBackgroundColor(BG);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14),dp(14),dp(14),dp(28)); root.setBackgroundColor(BG);
        s.addView(root,new ScrollView.LayoutParams(-1,-2)); setContentView(s);
    }
    private void brand() {
        TextView t=text("Soft4All  Fire Remote",25,Color.WHITE,true); t.setGravity(Gravity.CENTER); root.addView(t);
        TextView s=text("Fire TV 4K Remote · V6 · sin ADB",13,ORANGE,true); s.setGravity(Gravity.CENTER); root.addView(s);
    }

    private void showDiscovery() {
        prepareRoot(); brand();
        TextView h=text("Busca y enlaza tu Fire TV",20,Color.WHITE,true); h.setGravity(Gravity.CENTER); h.setPadding(0,dp(18),0,dp(4)); root.addView(h);
        status=text("Buscando Fire TV en tu red…",14,MUTED,false); status.setGravity(Gravity.CENTER); root.addView(status);
        LinearLayout devices=new LinearLayout(this); devices.setId(9001); devices.setOrientation(LinearLayout.VERTICAL); root.addView(devices);
        String last=prefs.getString("last_ip",""); if(!last.isEmpty()) addDeviceCard(devices,last,"Último Fire TV");
        Button scan=button("↻ Buscar de nuevo",BTN); scan.setOnClickListener(v->startScan()); root.addView(scan,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView note=text("No necesita ADB. La primera vez el Fire TV mostrará un PIN de 4 cifras para enlazar.",12,MUTED,false); note.setGravity(Gravity.CENTER); root.addView(note);
        startScan();
    }

    private void startScan() {
        if(scanning) return;
        LinearLayout devices=findViewById(9001); if(devices==null) return;
        scanning=true; status.setText("Buscando Fire TV en tu red…");
        discovery.execute(() -> {
            String me=localIpv4();
            if(me==null){ runOnUiThread(()->{scanning=false;status.setText("No pude identificar la red Wi‑Fi.");}); return; }
            String base=me.substring(0,me.lastIndexOf('.')+1);
            ExecutorService pool=Executors.newFixedThreadPool(64); AtomicInteger pending=new AtomicInteger(254), found=new AtomicInteger();
            for(int i=1;i<=254;i++){
                final String ip=base+i;
                pool.execute(() -> {
                    try {
                        if(!ip.equals(me) && (portOpen(ip,8080,80) || portOpen(ip,8009,80))) {
                            int n=found.incrementAndGet(); runOnUiThread(()->{addDeviceCard(devices,ip,"Fire TV detectado");status.setText("Encontrados: "+n);});
                        }
                    } finally {
                        if(pending.decrementAndGet()==0){pool.shutdown();scanning=false;runOnUiThread(()->status.setText(found.get()==0?"No encontré ningún Fire TV encendido en la red.":"Búsqueda terminada · "+found.get()+" dispositivo(s)"));}
                    }
                });
            }
        });
    }

    private void addDeviceCard(LinearLayout devices,String ip,String name){
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14),dp(9),dp(14),dp(9)); c.setBackground(outline(Color.rgb(18,31,45),BLUE,18));
        c.addView(text("▰  "+name,16,Color.WHITE,true)); c.addView(text(ip,14,Color.rgb(128,194,255),false)); c.setOnClickListener(v->pairOrConnect(ip));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(5),0,dp(5)); devices.addView(c,p);
    }

    private void pairOrConnect(String ip){
        host=ip; token=prefs.getString("token_"+ip,"");
        if(status!=null) status.setText("Conectando con "+ip+"…");
        discovery.execute(() -> {
            wake(ip);
            if(!token.isEmpty() && testToken(ip,token)) { prefs.edit().putString("last_ip",ip).apply(); runOnUiThread(()->showRemote(ip)); return; }
            try { Thread.sleep(450); } catch(Exception ignored){}
            if(displayPin(ip)) runOnUiThread(()->showPinDialog(ip));
            else runOnUiThread(()->status.setText("El Fire TV responde, pero no pudo iniciarse el emparejamiento por PIN."));
        });
    }

    private void showPinDialog(String ip){
        EditText pin=new EditText(this); pin.setHint("PIN de 4 cifras"); pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); pin.setGravity(Gravity.CENTER);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Enlazar Fire TV").setMessage("Mira el PIN que aparece en la televisión e introdúcelo aquí.").setView(pin)
                .setNegativeButton("Cancelar",null).setPositiveButton("Enlazar",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String p=pin.getText().toString().trim(); if(p.length()!=4){pin.setError("Introduce las 4 cifras");return;} verifyPin(ip,p,d);}));
        d.show();
    }

    private void verifyPin(String ip,String pin,AlertDialog dialog){
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        discovery.execute(() -> {
            String t="";
            for(int i=0;i<3 && t.isEmpty();i++){
                try {
                    Response r=call(ip,"/v1/FireTV/pin/verify",false,"{\"pin\":\""+pin+"\"}","").execute();
                    String body=r.body()!=null?r.body().string():""; r.close();
                    if(!body.isEmpty()){String desc=new JSONObject(body).optString("description",""); if(!desc.equals("OK")) t=desc;}
                    if(t.isEmpty()) Thread.sleep(250);
                } catch(Exception ignored){}
            }
            final String tok=t;
            runOnUiThread(() -> {
                if(tok.isEmpty()){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);Toast.makeText(this,"PIN no aceptado o token no recibido",Toast.LENGTH_SHORT).show();}
                else {token=tok;prefs.edit().putString("token_"+ip,tok).putString("last_ip",ip).apply();dialog.dismiss();showRemote(ip);}
            });
        });
    }

    private boolean displayPin(String ip){
        try { Response r=call(ip,"/v1/FireTV/pin/display",false,"{\"friendlyName\":\"Soft4All Fire Remote\"}","").execute(); boolean ok=r.isSuccessful(); r.close(); return ok; } catch(Exception e){return false;}
    }
    private boolean testToken(String ip,String tok){
        try { Request q=new Request.Builder().url("https://"+ip+":8080/v1/FireTV").header("X-Api-Key",API_KEY).header("X-Client-Token",tok).build(); Response r=http.newCall(q).execute(); boolean ok=r.code()!=401 && r.code()!=403; r.close(); return ok; } catch(Exception e){return false;}
    }
    private void wake(String ip){
        try { Request q=new Request.Builder().url("http://"+ip+":8009/apps/FireTVRemote").post(RequestBody.create(new byte[0],null)).build(); Response r=http.newCall(q).execute(); r.close(); } catch(Exception ignored){}
    }

    private Call call(String ip,String path,boolean auth,String body,String overrideToken){
        Request.Builder b=new Request.Builder().url("https://"+ip+":8080"+path).header("X-Api-Key",API_KEY).header("Content-Type","application/json; charset=utf-8");
        String t=overrideToken.isEmpty()?token:overrideToken; if(auth && t!=null && !t.isEmpty()) b.header("X-Client-Token",t);
        b.post(RequestBody.create(body==null?"":body,JSON)); return http.newCall(b.build());
    }

    private void sendNav(String action){ send("/v1/FireTV?action="+action,""); }
    private void sendMedia(String action){ send("/v1/media?action="+action,""); }
    private void sendScan(String dir){ send("/v1/media?action=scan&direction="+dir,""); }
    private void send(String path,String body){
        final String h=host,t=token; if(h==null||t==null||t.isEmpty()) return;
        control.execute(() -> {
            try { Request.Builder b=new Request.Builder().url("https://"+h+":8080"+path).header("X-Api-Key",API_KEY).header("X-Client-Token",t).header("Content-Type","application/json; charset=utf-8");
                Response r=http.newCall(b.post(RequestBody.create(body==null?"":body,JSON)).build()).execute(); r.close();
            } catch(Exception e){ wake(h); }
        });
    }

    private void showRemote(String ip){
        host=ip; token=prefs.getString("token_"+ip,token==null?"":token); prepareRoot(); brand();
        TextView c=text("● Conectado · "+ip,13,GREEN,true); c.setGravity(Gravity.CENTER); root.addView(c);
        LinearLayout shell=new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setPadding(dp(14),dp(16),dp(14),dp(18)); shell.setBackground(outline(PANEL,Color.rgb(48,58,69),34));
        root.addView(shell,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout top=row();
        Button power=button("⏻",Color.rgb(78,31,31)); power.setOnClickListener(v->sendNav("sleep"));
        Button voice=button("🎙",BLUE); voice.setTextSize(21); voice.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){startVoice();return true;} if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){stopVoice();return true;} return true;});
        Button change=button("TV",BTN); change.setOnClickListener(v->showDiscovery());
        flex(top,power,58);flex(top,voice,58);flex(top,change,58);shell.addView(top);

        LinearLayout up=row(); Button bu=button("▲",BTN);bu.setOnClickListener(v->sendNav("dpad_up"));flex(up,bu,76);shell.addView(up);
        LinearLayout mid=row(); Button bl=button("◀",BTN);bl.setOnClickListener(v->sendNav("dpad_left"));Button ok=button("OK",BTN);ok.setOnClickListener(v->sendNav("select"));Button br=button("▶",BTN);br.setOnClickListener(v->sendNav("dpad_right"));flex(mid,bl,76);flex(mid,ok,76);flex(mid,br,76);shell.addView(mid);
        LinearLayout down=row();Button bd=button("▼",BTN);bd.setOnClickListener(v->sendNav("dpad_down"));flex(down,bd,76);shell.addView(down);

        LinearLayout nav=row(); Button back=button("↩",BTN);back.setOnClickListener(v->sendNav("back"));Button home=button("⌂",BTN);home.setOnClickListener(v->sendNav("home"));Button menu=button("☰",BTN);menu.setOnClickListener(v->sendNav("menu"));flex(nav,back,58);flex(nav,home,58);flex(nav,menu,58);shell.addView(nav);
        LinearLayout media=row();Button rw=button("⏪",BTN);rw.setOnClickListener(v->sendScan("back"));Button pp=button("▶❚❚",BTN);pp.setOnClickListener(v->sendMedia("play"));Button ff=button("⏩",BTN);ff.setOnClickListener(v->sendScan("forward"));flex(media,rw,58);flex(media,pp,58);flex(media,ff,58);shell.addView(media);
        LinearLayout vol=row();Button mute=button("🔇",BTN);mute.setOnClickListener(v->sendNav("mute"));Button vd=button("−",BTN);vd.setOnClickListener(v->sendNav("volume_down"));Button vu=button("+",BTN);vu.setOnClickListener(v->sendNav("volume_up"));flex(vol,mute,58);flex(vol,vd,58);flex(vol,vu,58);shell.addView(vol);

        TextView at=text("Accesos directos",15,Color.WHITE,true);at.setGravity(Gravity.CENTER);shell.addView(at);
        LinearLayout a1=row();flex(a1,app("Prime",new String[]{"com.amazon.cloud9"}),54);flex(a1,app("Netflix",new String[]{"com.netflix.ninja"}),54);flex(a1,app("Disney+",new String[]{"com.disney.disneyplus"}),54);shell.addView(a1);
        LinearLayout a2=row();flex(a2,app("Max",new String[]{"com.wbd.stream","com.hbo.hbonow","com.discovery.discoplus"}),54);flex(a2,customApp(1),54);flex(a2,customApp(2),54);shell.addView(a2);

        TextView more=text("Más opciones",16,Color.WHITE,true);more.setGravity(Gravity.CENTER);more.setPadding(0,dp(14),0,dp(4));root.addView(more);
        textInput=new EditText(this); textInput.setTextColor(Color.WHITE); textInput.setHintTextColor(MUTED); textInput.setHint("Escribe en el Fire TV…"); textInput.setSingleLine(true); textInput.setBackground(outline(PANEL,Color.rgb(61,82,104),14)); root.addView(textInput,new LinearLayout.LayoutParams(-1,dp(56)));
        LinearLayout kb=row();Button send=button("Enviar texto",BLUE);send.setOnClickListener(v->sendKeyboard());Button apps=button("Configurar APP 1/2",BTN);apps.setOnClickListener(v->configureCustom(1));flex(kb,send,56);flex(kb,apps,56);root.addView(kb);
        TextView n=text("Mantén pulsado el botón azul de voz mientras hablas. El audio se envía directamente al canal de voz del Fire TV a 16 kHz mono.",12,MUTED,false);n.setGravity(Gravity.CENTER);root.addView(n);
    }

    private Button app(String label,String[] packages){ Button b=button(label,BTN); b.setOnClickListener(v->launchFirst(packages)); return b; }
    private Button customApp(int slot){
        String name=prefs.getString("app_name_"+slot,"APP "+slot); Button b=button(name,BTN);
        b.setOnClickListener(v->{String p=prefs.getString("app_pkg_"+slot,"");if(p.isEmpty())configureCustom(slot);else launchFirst(new String[]{p});});
        b.setOnLongClickListener(v->{configureCustom(slot);return true;}); return b;
    }
    private void launchFirst(String[] packages){ if(packages.length==0)return; launchTry(packages,0); }
    private void launchTry(String[] packages,int i){
        if(i>=packages.length)return; final String h=host,t=token,pkg=packages[i]; control.execute(()->{
            try{Request q=new Request.Builder().url("https://"+h+":8080/v1/FireTV/app/"+pkg).header("X-Api-Key",API_KEY).header("X-Client-Token",t).post(RequestBody.create(new byte[0],null)).build();Response r=http.newCall(q).execute();boolean ok=r.isSuccessful();r.close();if(!ok)runOnUiThread(()->launchTry(packages,i+1));}catch(Exception e){runOnUiThread(()->launchTry(packages,i+1));}
        });
    }

    private void configureCustom(int slot){
        discovery.execute(()->{
            List<String> names=new ArrayList<>(), pkgs=new ArrayList<>();
            try{Request q=new Request.Builder().url("https://"+host+":8080/v1/FireTV/appsV2").header("X-Api-Key",API_KEY).header("X-Client-Token",token).build();Response r=http.newCall(q).execute();String body=r.body()!=null?r.body().string():"";r.close();JSONArray a=new JSONArray(body);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("appId","");String n=o.optString("name",id);if(!id.isEmpty()){names.add(n);pkgs.add(id);}}}catch(Exception ignored){}
            runOnUiThread(()->{
                if(names.isEmpty()){showManualApp(slot);return;}
                String[] arr=names.toArray(new String[0]); new AlertDialog.Builder(this).setTitle("APP "+slot+" · elige aplicación").setItems(arr,(d,w)->{prefs.edit().putString("app_name_"+slot,names.get(w)).putString("app_pkg_"+slot,pkgs.get(w)).apply();showRemote(host);}).setNeutralButton("Introducir paquete",(d,w)->showManualApp(slot)).show();
            });
        });
    }
    private void showManualApp(int slot){ EditText e=new EditText(this);e.setHint("com.ejemplo.app");new AlertDialog.Builder(this).setTitle("APP "+slot).setMessage("Introduce el identificador de paquete de la aplicación.").setView(e).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->{String p=e.getText().toString().trim();if(!p.isEmpty()){prefs.edit().putString("app_name_"+slot,"APP "+slot).putString("app_pkg_"+slot,p).apply();showRemote(host);}}).show(); }

    private void sendKeyboard(){ if(textInput==null)return;String s=textInput.getText().toString();if(s.isEmpty())return;send("/v1/FireTV/keyboard","{\"text\":"+JSONObject.quote(s)+"}"); }

    private void startVoice(){
        if(voiceActive||host==null||token==null)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQ);Toast.makeText(this,"Concede permiso de micrófono y mantén pulsado otra vez",Toast.LENGTH_SHORT).show();return;}
        voiceActive=true;
        control.execute(()->{
            try{Response r=call(host,"/v1/FireTV/voiceCommand?action=start",true,"",token).execute();r.close();}catch(Exception ignored){}
            Request req=new Request.Builder().url("wss://"+host+":9090/").build();
            voiceSocket=http.newWebSocket(req,new WebSocketListener(){@Override public void onOpen(WebSocket ws,Response response){voiceSocket=ws;beginAudio(ws);}});
        });
    }
    private void beginAudio(WebSocket ws){
        audioExec.execute(()->{
            try{
                int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);int size=Math.max(2048,min);
                recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2);
                byte[] buf=new byte[640];recorder.startRecording();
                while(voiceActive){int n=recorder.read(buf,0,buf.length);if(n>0)ws.send(ByteString.of(buf,0,n));}
            }catch(Exception ignored){}finally{try{if(recorder!=null){recorder.stop();recorder.release();}}catch(Exception ignored){}recorder=null;}
        });
    }
    private void stopVoice(){
        if(!voiceActive)return;voiceActive=false;try{if(voiceSocket!=null)voiceSocket.close(1000,"done");}catch(Exception ignored){}voiceSocket=null;
        control.execute(()->{try{Response r=call(host,"/v1/FireTV/voiceCommand?action=stop",true,"",token).execute();r.close();}catch(Exception ignored){}});
    }

    private boolean portOpen(String h,int port,int timeout){try(Socket s=new Socket()){s.connect(new InetSocketAddress(h,port),timeout);return true;}catch(Exception e){return false;}}
    private String localIpv4(){try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;for(java.net.InetAddress a:Collections.list(ni.getInetAddresses()))if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&a.isSiteLocalAddress())return a.getHostAddress();}}catch(Exception ignored){}return null;}

    @Override protected void onDestroy(){super.onDestroy();voiceActive=false;discovery.shutdownNow();control.shutdownNow();audioExec.shutdownNow();if(http!=null)http.dispatcher().executorService().shutdown();}
}
