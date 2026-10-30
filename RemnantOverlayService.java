package com.hollowhaven.remnant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class RemnantOverlayService extends Service {
    public static final String ACTION_START = "com.hollowhaven.remnant.START";
    public static final String ACTION_STOP = "com.hollowhaven.remnant.STOP";
    public static final String ACTION_RELOAD = "com.hollowhaven.remnant.RELOAD";
    private static final String CHANNEL_ID = "hollowhaven_pet";
    private static final int NOTIFICATION_ID = 4141;
    private WindowManager wm; private PetView petView; private WindowManager.LayoutParams lp; private Handler handler;
    private final Random random = new Random(); private boolean running; private int screenW, screenH; private float x,y,vx,vy;
    private int direction=1; private String action="idle"; private int frameIndex; private long lastFrame,nextDecision;
    private boolean jumping,climbing,falling; private float speedMultiplier=1f; private int petSizeDp=170; private SharedPreferences prefs;
    private File[] customFrames;
    private final Map<String,File[]> customSequences=new HashMap<>();
    private boolean screenOn=true;
    private static final String[][] SEQUENCES = new String[][]{
        names(1,20),names(21,40),names(41,60),names(61,70),names(71,80),names(81,90),names(91,100),names(101,110),names(111,120),names(121,130),names(131,140),names(141,150),names(151,160),names(161,170),names(171,180),names(181,190),names(191,200)};
    private static String[] names(int a,int b){String[] s=new String[b-a+1];for(int i=a;i<=b;i++)s[i-a]=String.format("%03d",i);return s;}

    @Override public void onCreate(){
        super.onCreate(); handler=new Handler(getMainLooper()); wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        createNotificationChannel(); prefs=getSharedPreferences("remnant_pet",MODE_PRIVATE); loadPreferences(); loadCustomFrames(); updateScreenSize();
        IntentFilter f=new IntentFilter(); f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON); if(Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(screenReceiver,f);
        IntentFilter reloadFilter=new IntentFilter(ACTION_RELOAD); if(Build.VERSION.SDK_INT>=33) registerReceiver(reloadReceiver,reloadFilter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(reloadReceiver,reloadFilter);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && ACTION_STOP.equals(intent.getAction())){ cleanupAndStop(); return START_NOT_STICKY; }
        if(intent!=null && ACTION_RELOAD.equals(intent.getAction())){ reloadPet(); return START_NOT_STICKY; }
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}
        try{
            Notification n=buildNotification();
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(NOTIFICATION_ID,n);
            startPet();
        }catch(Throwable t){ cleanupAndStop(); }
        return START_STICKY;
    }

    private void startPet(){
        if(running)return;
        loadCustomFrames(); running=true; jumping=climbing=falling=false; vy=0; updateScreenSize();
        int size=dp(petSizeDp);
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        lp=new WindowManager.LayoutParams(size,size,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;
        x=Math.max(4,screenW*.42f); y=groundY(); vx=0; direction=1; action="idle"; frameIndex=0;
        nextDecision=System.currentTimeMillis()+900; lastFrame=0;
        petView=new PetView(this);
        try{ wm.addView(petView,lp); }
        catch(Throwable t){ petView=null; cleanupAndStop(); return; }
        handler.removeCallbacks(tick); handler.post(tick);
    }

    private void cleanupAndStop(){
        running=false;
        if(handler!=null) handler.removeCallbacksAndMessages(null);
        if(petView!=null){try{wm.removeView(petView);}catch(Throwable ignored){} petView=null;}
        try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(Throwable ignored){}
        stopSelf();
    }
    private void stopPet(){ cleanupAndStop(); }

    private final Runnable tick=new Runnable(){public void run(){
        if(!running||petView==null)return;
        if(!screenOn){ handler.postDelayed(this,500); return; }
        try{
            long now=System.currentTimeMillis(); updateScreenSize(); chooseAction(now);
            if(action.equals("walkR")||action.equals("walkL")) x+=vx*speedMultiplier;
            if(jumping){y+=vy;vy+=.34f;if(y>=groundY()){y=groundY();vy=0;jumping=false;setAction("idle");nextDecision=now+700;}}
            else if(falling){y+=vy;vy+=.48f;if(y>=groundY()){y=groundY();vy=0;falling=false;setAction("idle");nextDecision=now+900;}}
            else if(climbing){y-=1.25f*speedMultiplier;if(y<=80){y=80;climbing=false;falling=true;vy=.4f;setAction("fall");nextDecision=now+1200;}}
            clampPosition(); lp.x=Math.round(x); lp.y=Math.round(y); wm.updateViewLayout(petView,lp);
            int fps=Math.max(5,Math.min(24,prefs!=null?prefs.getInt("import_fps",12):12));
            long customInterval=(long)Math.max(42,1000L/fps);
            long interval=!customSequences.isEmpty()?customInterval:((action.startsWith("walk")||action.startsWith("climb"))?105:130);
            if(now-lastFrame>=interval){lastFrame=now;frameIndex++;petView.invalidate();}
            handler.postDelayed(this,16);
        }catch(Throwable t){cleanupAndStop();}
    }};

    private void chooseAction(long now){
        if(jumping||climbing||falling||now<nextDecision)return;
        float r=random.nextFloat(); int edge=dp(30); boolean l=x<edge, rr=x>screenW-lp.width-edge;
        if((l||rr)&&r<.16f){climbing=true;vx=0;setAction("climbUp");nextDecision=now+2200+random.nextInt(2600);}
        else if(r<.56f){direction=random.nextBoolean()?1:-1;setAction(direction>0?"walkR":"walkL");vx=direction*(.8f+random.nextFloat()*1.1f);nextDecision=now+2600+random.nextInt(4200);}
        else if(r<.72f){vx=0;setAction("idle");nextDecision=now+1600+random.nextInt(3600);}
        else if(r<.83f){vx=0;setAction("jump");jumping=true;vy=-8;nextDecision=now+1200;}
        else if(r<.90f){vx=0;setAction("happy");nextDecision=now+1000;}
        else if(r<.95f){vx=0;setAction("front");nextDecision=now+1200;}
        else{vx=0;setAction("sleep");nextDecision=now+5000;}
    }
    private void setAction(String a){if(!a.equals(action)){action=a;frameIndex=0;}}
    private float groundY(){int s=lp!=null?lp.height:dp(petSizeDp);return Math.max(dp(8),screenH-s-dp(8));}
    private void clampPosition(){
        int s=lp!=null?lp.width:dp(petSizeDp);
        boolean airborne=jumping||falling;
        if(x<4){x=4;if(!climbing&&!airborne){direction=1;vx=Math.abs(vx);setAction("walkR");}}
        if(x>screenW-s-4){x=Math.max(4,screenW-s-4);if(!climbing&&!airborne){direction=-1;vx=-Math.abs(vx);setAction("walkL");}}
        if(!climbing&&y>groundY()&&!jumping&&!falling)y=groundY();
        if(y<60&&!climbing&&!falling)y=60;
    }
    private void reloadPet(){
        if(!running||petView==null)return;
        loadPreferences();
        loadCustomFrames();
        customCacheClear();
        action="idle"; frameIndex=0; vx=0; vy=0; jumping=false; climbing=false; falling=false;
        updateScreenSize();
        resizePetWindow();
        x=Math.max(4,Math.min(x,Math.max(4,screenW-lp.width-4))); y=groundY();
        lp.x=Math.round(x); lp.y=Math.round(y);
        try{wm.updateViewLayout(petView,lp);}catch(Throwable ignored){}
        petView.invalidate();
    }

    private void resizePetWindow(){
        if(lp==null)return;
        int size=dp(petSizeDp);
        if(lp.width==size && lp.height==size)return;
        lp.width=size; lp.height=size;
        try{wm.updateViewLayout(petView,lp);}catch(Throwable ignored){}
    }
    private void customCacheClear(){ if(petView!=null) petView.cache.evictAll(); }
    private void loadPreferences(){speedMultiplier=prefs.getFloat("speed",1f);petSizeDp=Math.max(110,Math.min(260,prefs.getInt("size",170)));}
    private void loadCustomFrames(){
        customFrames=null; customSequences.clear();
        if(prefs==null||!"imported".equals(prefs.getString("selected_pet","remnant")))return;
        File dir=new File(getFilesDir(),"pets/imported");
        if(!dir.isDirectory())return;
        File[] root=dir.listFiles((d,n)->n.toLowerCase().endsWith(".png")||n.toLowerCase().endsWith(".jpg")||n.toLowerCase().endsWith(".jpeg"));
        if(root!=null&&root.length>0){Arrays.sort(root,(a,b)->a.getName().compareToIgnoreCase(b.getName()));customFrames=root;customSequences.put("default",root);}
        File[] dirs=dir.listFiles(File::isDirectory);
        if(dirs!=null) for(File sub:dirs){
            File[] fs=sub.listFiles((d,n)->n.toLowerCase().endsWith(".png")||n.toLowerCase().endsWith(".jpg")||n.toLowerCase().endsWith(".jpeg"));
            if(fs!=null&&fs.length>0){Arrays.sort(fs,(a,b)->a.getName().compareToIgnoreCase(b.getName()));customSequences.put(normalizeAction(sub.getName()),fs);}
        }
    }
    private String normalizeAction(String s){return s.toLowerCase().replace("-","_").replace(" ","_");}
    private File[] customForAction(String a){
        if(customSequences.isEmpty())return null;
        String key=a.toLowerCase();
        if(key.equals("walkr"))key="walk_right";
        else if(key.equals("walkl"))key="walk_left";
        else if(key.equals("climbup"))key="climb_up";
        else if(key.equals("climbdown"))key="climb_down";
        File[] f=customSequences.get(key); if(f!=null)return f;
        if(key.startsWith("walk")){f=customSequences.get("walk");if(f!=null)return f;}
        if(key.startsWith("climb")){f=customSequences.get("climb");if(f!=null)return f;}
        f=customSequences.get("idle"); if(f!=null)return f;
        return customSequences.get("default");
    }
    void setPetSpeed(float value){
        speedMultiplier=Math.max(0.35f,Math.min(2.5f,value));
        prefs.edit().putFloat("speed",speedMultiplier).apply();
    }

    void setPetSize(int value){
        petSizeDp=Math.max(110,Math.min(260,value));
        prefs.edit().putInt("size",petSizeDp).apply();
        reloadPet();
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void updateScreenSize(){
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics m = wm.getCurrentWindowMetrics();
                android.graphics.Insets in = m.getWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Rect b = m.getBounds();
                screenW = Math.max(1, b.width() - in.left - in.right);
                screenH = Math.max(1, b.height() - in.top - in.bottom);
            } else {
                android.util.DisplayMetrics d=getResources().getDisplayMetrics();
                screenW=d.widthPixels; screenH=d.heightPixels;
            }
        } catch (Throwable ignored) {
            android.util.DisplayMetrics d=getResources().getDisplayMetrics();
            screenW=d.widthPixels; screenH=d.heightPixels;
        }
    }
    private void createNotificationChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Hollowhaven Pet",NotificationManager.IMPORTANCE_LOW);c.setDescription("Hollowhaven screen pet");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification buildNotification(){Intent i=new Intent(this,MainActivity.class);PendingIntent p=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Intent stop=new Intent(this,RemnantOverlayService.class).setAction(ACTION_STOP);PendingIntent sp=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);return b.setContentTitle("Hollowhaven Remnant").setContentText("The pet is active on your screen.").setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(p).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"REST",sp).build();}
    private final BroadcastReceiver reloadReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(ACTION_RELOAD.equals(i.getAction())) reloadPet();
    }};
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){screenOn=false;}
        else if(Intent.ACTION_SCREEN_ON.equals(i.getAction())){screenOn=true;if(running){lastFrame=0;handler.removeCallbacks(tick);handler.post(tick);}}
    }};
    @Override public void onDestroy(){
        try{unregisterReceiver(screenReceiver);}catch(Throwable ignored){}
        try{unregisterReceiver(reloadReceiver);}catch(Throwable ignored){}
        cleanupAndStop();super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}

    private class PetView extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);final android.util.LruCache<String,Bitmap> cache=new android.util.LruCache<>(24);float downX,downY,startX,startY;boolean dragging;
        PetView(Context c){super(c);setBackground(new ColorDrawable(Color.TRANSPARENT));}
        protected void onDraw(android.graphics.Canvas c){
            super.onDraw(c); Bitmap b=getBitmap(); if(b==null)return;
            // preserve aspect so AI video frames do not stretch into a square.
            float scale=Math.min((float)getWidth()/Math.max(1,b.getWidth()),(float)getHeight()/Math.max(1,b.getHeight()));
            float dw=b.getWidth()*scale, dh=b.getHeight()*scale;
            float left=(getWidth()-dw)/2f, top=(getHeight()-dh)/2f;
            c.save(); c.scale(direction<0?-1:1,1,getWidth()/2f,getHeight()/2f);
            c.drawBitmap(b,null,new android.graphics.RectF(left,top,left+dw,top+dh),paint); c.restore();
        }
        private Bitmap getBitmap(){
            if(!customSequences.isEmpty()){
                File[] seq=customForAction(action);
                if(seq!=null&&seq.length>0){String key=seq[Math.floorMod(frameIndex,seq.length)].getAbsolutePath();Bitmap b=cache.get(key);if(b==null){b=BitmapFactory.decodeFile(key);if(b!=null)cache.put(key,b);}return b;}
            }
            String n=currentFrameName();String key="res:"+n;Bitmap b=cache.get(key);if(b!=null)return b;int id=getResources().getIdentifier("f"+n,"drawable",getPackageName());if(id==0)return null;b=BitmapFactory.decodeResource(getResources(),id);if(b!=null)cache.put(key,b);return b;
        }
        public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:dragging=true;downX=e.getRawX();downY=e.getRawY();startX=x;startY=y;vx=0;setAction("happy");return true;
            case MotionEvent.ACTION_MOVE:if(dragging){x=startX+(e.getRawX()-downX);y=startY+(e.getRawY()-downY);lp.x=Math.round(x);lp.y=Math.round(y);try{wm.updateViewLayout(petView,lp);}catch(Throwable ignored){}invalidate();}return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                float dx=e.getRawX()-downX,dy=e.getRawY()-downY; dragging=false;
                if(Math.abs(dx)<dp(14)&&Math.abs(dy)<dp(14)){vx=0;setAction("happy");nextDecision=System.currentTimeMillis()+1400;}
                else {direction=e.getRawX()>=downX?1:-1;vx=direction*.9f;setAction(direction>0?"walkR":"walkL");nextDecision=System.currentTimeMillis()+1600;}
                return true;
        }return true;}
    }
    private String currentFrameName(){String[] f=SEQUENCES[sequenceIndex(action)];return f[Math.floorMod(frameIndex,f.length)];}
    private int sequenceIndex(String a){switch(a){case "walkR":return 1;case "walkL":return 2;case "front":return 3;case "back":return 4;case "diagFR":return 5;case "diagFL":return 6;case "diagBR":return 7;case "diagBL":return 8;case "jump":return 9;case "fall":return 10;case "climbUp":return 11;case "climbDown":return 12;case "sleep":return 13;case "happy":return 14;case "elemental":return 15;case "extra":return 16;default:return 0;}}
}
