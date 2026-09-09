package com.soft4all.fireremote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import dadb.Dadb;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Dadb adb;
    private EditText ip, text;
    private TextView status;
    private LinearLayout root;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        System.setProperty("user.home", getFilesDir().getAbsolutePath());
        buildUi();
    }

    private TextView label(String s, int size) {
        TextView v=new TextView(this); v.setText(s); v.setTextColor(Color.WHITE); v.setTextSize(size); v.setGravity(Gravity.CENTER); v.setPadding(8,10,8,10); return v;
    }
    private Button btn(String s, View.OnClickListener l) {
        Button b=new Button(this); b.setText(s); b.setTextSize(17); b.setMinHeight(62); b.setOnClickListener(l); return b;
    }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER); return l; }
    private void addFlex(LinearLayout r, View v) { r.addView(v,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1)); }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this);
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(18,22,18,30); root.setBackgroundColor(Color.rgb(17,19,21));
        scroll.addView(root); setContentView(scroll);
        TextView title=label("SOFT4ALL FIRE REMOTE",24); root.addView(title);
        root.addView(label("Mando para Fire TV por red local",14));

        LinearLayout connect=row();
        ip=new EditText(this); ip.setHint("IP del Fire TV, ej. 192.168.43.25"); ip.setTextColor(Color.WHITE); ip.setHintTextColor(Color.GRAY); ip.setSingleLine(true); ip.setInputType(InputType.TYPE_CLASS_PHONE);
        connect.addView(ip,new LinearLayout.LayoutParams(0,70,2)); addFlex(connect,btn("CONECTAR",v->connect())); root.addView(connect);
        status=label("Desconectado",14); root.addView(status);

        LinearLayout r1=row(); addFlex(r1,btn("↑",v->key(19))); root.addView(r1);
        LinearLayout r2=row(); addFlex(r2,btn("←",v->key(21))); addFlex(r2,btn("OK",v->key(23))); addFlex(r2,btn("→",v->key(22))); root.addView(r2);
        LinearLayout r3=row(); addFlex(r3,btn("↓",v->key(20))); root.addView(r3);

        LinearLayout nav=row(); addFlex(nav,btn("ATRÁS",v->key(4))); addFlex(nav,btn("HOME",v->key(3))); addFlex(nav,btn("MENÚ",v->key(82))); root.addView(nav);
        LinearLayout media=row(); addFlex(media,btn("⏪",v->key(89))); addFlex(media,btn("▶ / ❚❚",v->key(85))); addFlex(media,btn("⏩",v->key(90))); root.addView(media);
        LinearLayout vol=row(); addFlex(vol,btn("VOL −",v->key(25))); addFlex(vol,btn("MUTE",v->key(164))); addFlex(vol,btn("VOL +",v->key(24))); root.addView(vol);

        text=new EditText(this); text.setHint("Texto para escribir en el Fire TV"); text.setTextColor(Color.WHITE); text.setHintTextColor(Color.GRAY); text.setSingleLine(true); root.addView(text);
        root.addView(btn("ENVIAR TEXTO",v->sendText()));
        root.addView(label("El Fire TV debe estar en la misma red y tener depuración ADB disponible. La primera conexión puede pedir autorización en la TV.",12));
    }

    private void connect() {
        String host=ip.getText().toString().trim();
        if(host.isEmpty()){ status.setText("Escribe la IP del Fire TV"); return; }
        status.setText("Conectando a "+host+":5555…");
        io.execute(()->{
            try {
                if(adb!=null) adb.close();
                adb=Dadb.create(host,5555);
                String out=adb.shell("echo Soft4All_OK").getAllOutput();
                runOnUiThread(()->status.setText(out.contains("Soft4All_OK")?"CONECTADO — "+host:"Conectado, respuesta inesperada"));
            } catch(Exception e){ runOnUiThread(()->status.setText("No se pudo conectar: "+e.getClass().getSimpleName())); }
        });
    }
    private void key(int code){ shell("input keyevent "+code); }
    private void sendText(){ String s=text.getText().toString(); if(!s.isEmpty()) shell("input text '"+s.replace("'","\\'").replace(" ","%s")+"'"); }
    private void shell(String cmd){
        if(adb==null){ status.setText("Primero conecta con el Fire TV"); return; }
        io.execute(()->{ try{ adb.shell(cmd); }catch(Exception e){ runOnUiThread(()->status.setText("Error enviando comando: "+e.getClass().getSimpleName())); }});
    }
    @Override protected void onDestroy(){ super.onDestroy(); io.shutdownNow(); try{if(adb!=null)adb.close();}catch(Exception ignored){} }
}
