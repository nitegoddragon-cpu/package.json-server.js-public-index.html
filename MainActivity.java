package com.hollowhaven.remnant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int TREE_REQ = 9001;
    private static final int NOTIFICATION_REQ = 9002;
    private static final int VIDEO_REQ = 9003;
    private static final int ZIP_REQ = 9004;
    private static final int REF_REQ = 9005;
    private static final int DRIVE_REQ = 9006;
    private static final int MAX_IMPORTED_FRAMES = 240;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PET_IMAGE = 320;

    private WebView web;
    private SharedPreferences prefs;
    private boolean pendingStartAfterNotification;
    private final AtomicBoolean importBusy = new AtomicBoolean(false);
    private Uri aiReferenceUri;
    private Uri aiDrivingUri;
    private AiVideoWorkerClient aiWorker;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("remnant_pet", MODE_PRIVATE);
        aiWorker = new AiVideoWorkerClient(this);
        buildWebApp();
    }

    private void buildWebApp() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new NativeBridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/hollowhaven/index.html");
    }

    private boolean overlayReady() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private boolean notificationsReady() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestOverlay() {
        if (overlayReady()) { toast("Overlay permission is already enabled."); return; }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
            catch (Exception ignored) { toast("Android did not open overlay settings."); }
        }
    }

    private void requestNotificationsAndMaybeStart() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsReady()) {
            pendingStartAfterNotification = true;
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQ);
                return;
            } catch (Throwable ignored) {
                pendingStartAfterNotification = false;
            }
        }
        startPetNow();
    }

    private void startPet() {
        if (!overlayReady()) {
            toast("Allow Hollowhaven to display over other apps first.");
            requestOverlay();
            return;
        }
        requestNotificationsAndMaybeStart();
    }

    private void startPetNow() {
        Intent i = new Intent(this, RemnantOverlayService.class).setAction(RemnantOverlayService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
            toast("The Remnant is awake.");
        } catch (Throwable e) {
            toast("Android blocked the pet service. The app stayed open safely.");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQ && pendingStartAfterNotification) {
            pendingStartAfterNotification = false;
            startPetNow();
        }
    }

    private void stopPet() {
        try { stopService(new Intent(this, RemnantOverlayService.class)); }
        catch (Throwable ignored) {}
        toast("The Remnant is resting.");
    }

    private void reloadRunningPet() {
        try {
            Intent i = new Intent(RemnantOverlayService.ACTION_RELOAD).setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private void openVideoPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("video/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, VIDEO_REQ);
        } catch (Throwable e) { toast("Android could not open the video picker."); }
    }

    private void openFolderPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, TREE_REQ);
        } catch (Throwable e) { toast("Android could not open the folder picker."); }
    }

    private void openZipPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("application/zip");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, ZIP_REQ);
        } catch (Throwable e) { toast("Android could not open the pet-pack picker."); }
    }

    private void chooseAiReference() {
        try { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,REF_REQ); } catch(Throwable e){ toast("Could not open the reference image picker."); }
    }
    private void useBuiltInAiReference(){
        try { File out=new File(getCacheDir(),"remnant-reference.png"); try(InputStream in=getAssets().open("hollowhaven/sprites/f001.png");OutputStream o=new FileOutputStream(out)){byte[]b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);} aiReferenceUri=Uri.fromFile(out); toast("Built-in Remnant reference selected."); evalAi("window.nativeAiSelection && window.nativeAiSelection('reference');"); } catch(Throwable e){toast("Could not prepare the built-in Remnant reference.");}
    }
    private void chooseAiDriving() {
        try { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("video/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,DRIVE_REQ); } catch(Throwable e){ toast("Could not open the driving-video picker."); }
    }
    private String aiWorkerUrl(){ return prefs.getString("ai_worker_url",""); }
    private void setAiWorkerUrl(String url){ prefs.edit().putString("ai_worker_url",url==null?"":url.trim()).apply(); }
    private void testAiWorker(){ String u=aiWorkerUrl(); if(u.isEmpty()){ toast("Enter the GPU worker URL first."); return; } toast("Checking GPU worker…"); aiWorker.health(u,(ok,msg,value)->runOnUiThread(()->{ if(ok){ toast("GPU worker is online."); evalAi("window.nativeWorkerHealth && window.nativeWorkerHealth(true,"+js(msg)+");"); } else { toast("GPU worker check failed: "+msg); evalAi("window.nativeWorkerHealth && window.nativeWorkerHealth(false,"+js(msg)+");"); }})); }
    private void renderAiVideo(String prompt){
        if(aiReferenceUri==null||aiDrivingUri==null){ toast("Choose both a Remnant reference image and a driving video first."); return; }
        String u=aiWorkerUrl(); if(u.isEmpty()){ toast("Enter the GPU worker URL first."); return; }
        toast("Uploading to the NVIDIA worker…");
        aiWorker.render(u,aiReferenceUri,aiDrivingUri,prompt,10,(ok,msg,value)->{
            if(!ok){runOnUiThread(()->{toast("GPU render request failed: "+msg);evalAi("window.nativeWorkerProgress && window.nativeWorkerProgress('failed',"+js(msg)+");");});return;}
            runOnUiThread(()->{toast("GPU job queued.");evalAi("window.nativeWorkerProgress && window.nativeWorkerProgress('queued','"+js(value)+"');");});
            aiWorker.pollUntilDone(u,value,(pok,pmsg,pvalue)->{
                if("progress".equals(pmsg)){ runOnUiThread(()->evalAi("window.nativeWorkerProgress && window.nativeWorkerProgress("+js(pvalue)+",'');")); return; }
                if(!pok){runOnUiThread(()->{toast("GPU render failed: "+pmsg);evalAi("window.nativeWorkerProgress && window.nativeWorkerProgress('failed',"+js(pmsg)+");");});return;}
                new Thread(()->{ try { File f=aiWorker.downloadResult(pvalue); importVideoFile(f); } catch(Throwable e){runOnUiThread(()->toast("Could not import GPU result: "+e.getMessage()));} },"wan-download").start();
            });
        });
    }
    private void importVideoFile(File file){ if(!beginImport())return; toast("Converting GPU video into pet frames…"); new Thread(()->{ MediaMetadataRetriever r=new MediaMetadataRetriever(); try{r.setDataSource(file.getAbsolutePath());String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);long ms=d==null?0:Long.parseLong(d);if(ms<=0)throw new IllegalStateException("No video duration");long usable=Math.min(ms,15000L);int count=Math.max(2,Math.min(180,(int)Math.ceil(usable/1000.0*12.0)));File root=new File(getFilesDir(),"pets/imported"),tmp=new File(getFilesDir(),"pets/importing");deleteTree(tmp);if(!tmp.mkdirs()&&!tmp.isDirectory())throw new IllegalStateException("Cannot create staging folder");for(int i=0;i<count;i++){long t=(long)((usable*1000.0)*i/Math.max(1,count-1));Bitmap b=r.getFrameAtTime(t,MediaMetadataRetriever.OPTION_CLOSEST);if(b==null)continue;Bitmap f=fitBitmap(b,MAX_PET_IMAGE,MAX_PET_IMAGE);Bitmap tr=removeSimpleBackground(f);if(tr!=f)f.recycle();writePng(tr,new File(tmp,String.format(Locale.US,"f%03d.png",i+1)));tr.recycle();}File[] frames=listImageFiles(tmp);if(frames.length<2)throw new IllegalStateException("GPU video contained too few frames");replaceImported(tmp,root);saveImported(frames.length,12,"gpu-worker");int n=frames.length;runOnUiThread(()->{toast("GPU Remnant imported: "+n+" frames.");notifyWebImported(n);evalAi("window.nativeWorkerProgress && window.nativeWorkerProgress('imported','"+n+"');");});}catch(Throwable e){deleteTree(new File(getFilesDir(),"pets/importing"));runOnUiThread(()->toast("GPU video import failed safely: "+e.getMessage()));}finally{try{r.release();}catch(Throwable ignored){}file.delete();importBusy.set(false);}},"gpu-video-import").start(); }
    private void evalAi(String js){ if(web!=null)web.post(()->web.evaluateJavascript(js,null)); }
    private static String js(String s){return "\""+(s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," "))+"\"";}

    private void resetToRemnant() {
        try {
            File root = new File(getFilesDir(), "pets/imported");
            deleteTree(root);
            prefs.edit().putString("selected_pet", "remnant").putInt("imported_count", 0).putInt("import_fps", 12).remove("import_source").apply();
            reloadRunningPet();
            toast("The original Remnant is active again.");
            notifyWebImported(0);
        } catch (Throwable e) { toast("Could not reset the pet library safely."); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.postDelayed(() -> web.evaluateJavascript("window.nativeState && window.nativeState(" + overlayReady() + "," + importedCount() + ");", null), 250);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri selected = data.getData();
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) getContentResolver().takePersistableUriPermission(selected, flags);
        } catch (Throwable ignored) {}

        if (requestCode == REF_REQ) { aiReferenceUri=selected; toast("AI reference image selected."); evalAi("window.nativeAiSelection && window.nativeAiSelection('reference');"); return; }
        if (requestCode == DRIVE_REQ) { aiDrivingUri=selected; toast("AI driving video selected."); evalAi("window.nativeAiSelection && window.nativeAiSelection('driving');"); return; }
        if (requestCode == ZIP_REQ) { if (!beginImport()) return; toast("Importing pet pack…"); new Thread(() -> importZip(selected), "pet-zip-import").start(); return; }
        if (requestCode == VIDEO_REQ) { if (!beginImport()) return; toast("Converting AI video into pet frames…"); new Thread(() -> importVideo(selected), "pet-video-import").start(); return; }
        if (requestCode == TREE_REQ) { if (!beginImport()) return; toast("Reading pet animation folder…"); new Thread(() -> importFolder(selected), "pet-folder-import").start(); }
    }

    private void importZip(Uri zip) {
        File petRoot = new File(getFilesDir(), "pets/imported");
        File tempRoot = new File(getFilesDir(), "pets/importing");
        try {
            deleteTree(tempRoot);
            if (!tempRoot.mkdirs() && !tempRoot.isDirectory()) throw new IllegalStateException("Cannot create import staging folder");
            int count = 0;
            HashMap<String,Integer> sequenceCounts = new HashMap<>();
            try (InputStream raw = getContentResolver().openInputStream(zip)) {
                if (raw == null) throw new IllegalStateException("Cannot open ZIP");
                try (ZipInputStream zin = new ZipInputStream(raw)) {
                    ZipEntry e;
                    while (count < MAX_IMPORTED_FRAMES && (e = zin.getNextEntry()) != null) {
                        if (e.isDirectory()) continue;
                        String safe = safeZipPath(e.getName());
                        if (safe == null || !isImageName(safe)) continue;
                        byte[] bytes = readLimited(zin, MAX_IMAGE_BYTES);
                        Bitmap bitmap = decodeNormalized(bytes);
                        if (bitmap == null) continue;
                        String actionDir = firstAnimationFolder(safe);
                        String key = actionDir == null ? "default" : actionDir;
                        int seq = sequenceCounts.getOrDefault(key, 0) + 1;
                        sequenceCounts.put(key, seq);
                        File out = actionDir == null
                                ? new File(tempRoot, String.format(Locale.US, "f%03d.png", seq))
                                : new File(new File(tempRoot, actionDir), String.format(Locale.US, "f%03d.png", seq));
                        File parent = out.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) { bitmap.recycle(); continue; }
                        writePng(bitmap, out);
                        bitmap.recycle();
                        count++;
                    }
                }
            }
            if (count < 1) throw new IllegalStateException("No usable pet frames");
            replaceImported(tempRoot, petRoot);
            saveImported(count, 12, "zip");
            final int imported = count;
            runOnUiThread(() -> { toast(imported > 0 ? "Pet pack imported: " + imported + " frames." : "No usable PNG/JPG frames were found."); notifyWebImported(imported); });
        } catch (Throwable e) {
            deleteTree(tempRoot);
            runOnUiThread(() -> toast("Pet-pack import failed safely. Your current pet was kept."));
        } finally {
            importBusy.set(false);
        }
    }

    private void importVideo(Uri video) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, video);
            String durText = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durText == null ? 0 : Long.parseLong(durText);
            if (durationMs <= 0) throw new IllegalStateException("No video duration");
            long usableMs = Math.min(durationMs, 15_000L);
            int count = Math.max(2, Math.min(180, (int)Math.ceil(usableMs / 1000.0 * 12.0)));
            File petRoot = new File(getFilesDir(), "pets/imported");
            File tempRoot = new File(getFilesDir(), "pets/importing");
            deleteTree(tempRoot);
            if (!tempRoot.mkdirs() && !tempRoot.isDirectory()) throw new IllegalStateException("Cannot create import staging folder");

            for (int i = 0; i < count; i++) {
                long tUs = (long)((usableMs * 1000.0) * i / Math.max(1, count - 1));
                Bitmap frame = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) continue;
                Bitmap fitted = fitBitmap(frame, MAX_PET_IMAGE, MAX_PET_IMAGE);
                if (fitted != frame) frame.recycle();
                Bitmap transparent = removeSimpleBackground(fitted);
                if (transparent != fitted) fitted.recycle();
                File dest = new File(tempRoot, String.format(Locale.US, "f%03d.png", i + 1));
                writePng(transparent, dest);
                transparent.recycle();
            }
            File[] frames = listImageFiles(tempRoot);
            if (frames.length < 2) throw new IllegalStateException("Video did not contain enough frames");
            replaceImported(tempRoot, petRoot);
            saveImported(frames.length, 12, "video");
            final int imported = frames.length;
            runOnUiThread(() -> { toast("AI video converted into " + imported + " transparent pet frames."); notifyWebImported(imported); });
        } catch (Throwable e) {
            deleteTree(new File(getFilesDir(), "pets/importing"));
            runOnUiThread(() -> toast("AI video import failed safely. Your current pet was kept."));
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
            importBusy.set(false);
        }
    }

    private void importFolder(Uri tree) {
        File petRoot = new File(getFilesDir(), "pets/imported");
        File tempRoot = new File(getFilesDir(), "pets/importing");
        try {
            deleteTree(tempRoot);
            if (!tempRoot.mkdirs() && !tempRoot.isDirectory()) throw new IllegalStateException("Cannot create import staging folder");
            ArrayList<ImageSource> images = new ArrayList<>();
            collectImages(tree, tree, "", images);
            Collections.sort(images, Comparator.comparing(a -> a.relative.toLowerCase(Locale.ROOT)));
            int count = 0;
            HashMap<String,Integer> sequenceCounts = new HashMap<>();
            for (ImageSource source : images) {
                if (count >= MAX_IMPORTED_FRAMES) break;
                try (InputStream in = getContentResolver().openInputStream(source.uri)) {
                    if (in == null) continue;
                    byte[] bytes = readLimited(in, MAX_IMAGE_BYTES);
                    Bitmap bitmap = decodeNormalized(bytes);
                    if (bitmap == null) continue;
                    String relative = sanitizeRelative(source.relative);
                    if (relative == null) { bitmap.recycle(); continue; }
                    String dirName = firstAnimationFolder(relative);
                    if (dirName == null) dirName = "";
                    int seq = sequenceCounts.getOrDefault(dirName, 0) + 1;
                    sequenceCounts.put(dirName, seq);
                    File parent = dirName.isEmpty() ? tempRoot : new File(tempRoot, dirName);
                    if (!parent.exists() && !parent.mkdirs()) { bitmap.recycle(); continue; }
                    File pngOut = new File(parent, String.format(Locale.US, "f%03d.png", seq));
                    writePng(bitmap, pngOut);
                    bitmap.recycle();
                    count++;
                }
            }
            if (count < 1) throw new IllegalStateException("No usable pet frames");
            replaceImported(tempRoot, petRoot);
            saveImported(count, 12, "folder");
            final int imported = count;
            runOnUiThread(() -> { toast(imported > 0 ? "Imported " + imported + " animation frames." : "No PNG/JPG animation frames were found."); notifyWebImported(imported); });
        } catch (Throwable e) {
            deleteTree(tempRoot);
            runOnUiThread(() -> toast("Pet import failed safely. Your current pet was kept."));
        } finally {
            importBusy.set(false);
        }
    }

    private void collectImages(Uri treeUri, Uri parentDocumentUri, String relative, List<ImageSource> result) {
        if (result.size() >= MAX_IMPORTED_FRAMES) return;
        String docId = android.provider.DocumentsContract.getDocumentId(parentDocumentUri);
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        try (android.database.Cursor c = getContentResolver().query(children,
                new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext() && result.size() < MAX_IMPORTED_FRAMES) {
                String id = c.getString(0), mime = c.getString(1), name = c.getString(2);
                Uri child = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    String next = relative.isEmpty() ? sanitizeDirectory(name) : relative + "/" + sanitizeDirectory(name);
                    if (next != null) collectImages(treeUri, child, next, result);
                } else if (isImage(mime, name)) {
                    String safeName = sanitizeFilename(name);
                    if (safeName != null) result.add(new ImageSource(child, relative.isEmpty() ? safeName : relative + "/" + safeName));
                }
            }
        } catch (Throwable ignored) {}
    }

    private Bitmap decodeNormalized(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (decoded == null) return null;
        return fitBitmap(decoded, MAX_PET_IMAGE, MAX_PET_IMAGE);
    }

    private Bitmap fitBitmap(Bitmap src, int maxW, int maxH) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min((float)maxW / Math.max(1, w), (float)maxH / Math.max(1, h));
        int nw = Math.max(1, Math.round(w * Math.min(1f, scale)));
        int nh = Math.max(1, Math.round(h * Math.min(1f, scale)));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        Bitmap out = scaled.copy(Bitmap.Config.ARGB_8888, false);
        if (scaled != src) scaled.recycle();
        if (src != scaled) src.recycle();
        return out;
    }

    private Bitmap removeSimpleBackground(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        int[] samples = new int[]{px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1]};
        boolean[] seen = new boolean[px.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < w; x++) { q.add(x); q.add((h - 1) * w + x); }
        for (int y = 0; y < h; y++) { q.add(y * w); q.add(y * w + w - 1); }
        int removed = 0;
        while (!q.isEmpty()) {
            int idx = q.removeFirst();
            if (idx < 0 || idx >= px.length || seen[idx]) continue;
            seen[idx] = true;
            int color = px[idx];
            if (!closeToAny(color, samples, 58)) continue;
            if (((color >>> 24) & 0xff) != 0) { px[idx] = color & 0x00ffffff; removed++; }
            int x = idx % w, y = idx / w;
            if (x > 0) q.add(idx - 1); if (x + 1 < w) q.add(idx + 1);
            if (y > 0) q.add(idx - w); if (y + 1 < h) q.add(idx + w);
        }
        if (removed < px.length * 0.01 || removed > px.length * 0.88) return out;
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private boolean closeToAny(int c, int[] samples, int tolerance) {
        int a = (c >>> 24) & 0xff, r = (c >>> 16) & 0xff, g = (c >>> 8) & 0xff, b = c & 0xff;
        if (a == 0) return true;
        for (int s : samples) {
            int sr=(s>>>16)&0xff, sg=(s>>>8)&0xff, sb=s&0xff;
            int d=Math.abs(r-sr)+Math.abs(g-sg)+Math.abs(b-sb);
            if(d<=tolerance) return true;
        }
        return false;
    }

    private byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(65536, maxBytes));
        byte[] buf = new byte[16384]; int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new IllegalStateException("Image too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void writePng(Bitmap bitmap, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create folder");
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) throw new IllegalStateException("PNG encode failed");
        }
    }

    private void replaceImported(File tempRoot, File petRoot) {
        File parent = petRoot.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create pet library");
        }
        File backup = new File(parent, "imported_backup");
        deleteTree(backup);
        boolean hadCurrent = petRoot.exists();
        if (hadCurrent && !petRoot.renameTo(backup)) {
            throw new IllegalStateException("Could not stage current pet safely");
        }
        if (tempRoot.renameTo(petRoot)) {
            deleteTree(backup);
            return;
        }
        if (hadCurrent) petRoot.delete();
        if (hadCurrent && !backup.renameTo(petRoot)) {
            throw new IllegalStateException("Could not restore current pet after import failure");
        }
        throw new IllegalStateException("Could not activate imported pet");
    }

    private boolean beginImport() {
        if (!importBusy.compareAndSet(false, true)) {
            toast("A pet import is already running. Please wait for it to finish.");
            return false;
        }
        return true;
    }

    private void saveImported(int count, int fps, String source) {
        prefs.edit().putString("selected_pet", count > 0 ? "imported" : "remnant")
                .putInt("imported_count", count).putInt("import_fps", fps).putString("import_source", source).apply();
        reloadRunningPet();
    }

    private String firstAnimationFolder(String relative) {
        if (relative == null) return null;
        String[] parts = relative.replace('\\', '/').split("/");
        for (String part : parts) {
            String safe = sanitizeDirectory(part);
            if (safe != null && isAnimationFolder(safe)) {
                String n = safe.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
                if (n.equals("climb")) return "climb_up";
                return n;
            }
        }
        return null;
    }

    private boolean isAnimationFolder(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        return n.equals("idle") || n.equals("walk") || n.equals("walk_left") || n.equals("walk_right")
                || n.equals("jump") || n.equals("fall") || n.equals("climb") || n.equals("climb_up")
                || n.equals("climb_down") || n.equals("sleep") || n.equals("happy") || n.equals("front")
                || n.equals("back") || n.equals("diagfr") || n.equals("diagfl") || n.equals("diagbr")
                || n.equals("diagbl") || n.equals("elemental") || n.equals("extra");
    }

    private String safeZipPath(String raw) {
        if (raw == null) return null;
        String normalized = raw.replace('\\', '/');
        String[] parts = normalized.split("/");
        ArrayList<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part) || part.indexOf(':') >= 0) return null;
            String safe = sanitizeFilename(part);
            if (safe == null) return null;
            clean.add(safe);
        }
        return clean.isEmpty() ? null : String.join("/", clean);
    }

    private String safeBasePath(String safe, int index) {
        int slash = safe.lastIndexOf('/');
        String dir = slash >= 0 ? safe.substring(0, slash) : "";
        String ext = ".png";
        return (dir.isEmpty() ? "" : dir + "/") + String.format(Locale.US, "frame_%03d%s", index + 1, ext);
    }

    private String sanitizeRelative(String s) {
        if (s == null) return null;
        String[] parts=s.replace('\\','/').split("/"); ArrayList<String> clean=new ArrayList<>();
        for(String part:parts){ if(part.isEmpty()||".".equals(part))continue; if("..".equals(part))return null; String x=sanitizeFilename(part); if(x==null)return null; clean.add(x); }
        return clean.isEmpty()?null:String.join("/",clean);
    }

    private String sanitizeDirectory(String s){
        return sanitizeFilename(s);
    }
    private String sanitizeFilename(String s){
        if(s==null)return null; String x=s.replaceAll("[\\\\/:*?\"<>|]","_").trim(); if(x.isEmpty()||".".equals(x)||"..".equals(x))return null; return x;
    }
    private String stripExtension(String s){int i=s.lastIndexOf('.');return i>0?s.substring(0,i):s;}
    private boolean isImage(String mime,String name){String n=name==null?"":name.toLowerCase(Locale.ROOT);return(mime!=null&&mime.startsWith("image/"))||isImageName(n);}
    private boolean isImageName(String n){String x=n.toLowerCase(Locale.ROOT);return x.endsWith(".png")||x.endsWith(".jpg")||x.endsWith(".jpeg");}
    private File[] listImageFiles(File dir){File[] fs=dir.listFiles((d,n)->isImageName(n));if(fs==null)return new File[0];return fs;}
    private void deleteTree(File f){if(!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}f.delete();}
    private int importedCount(){return prefs.getInt("imported_count",0);}
    private void notifyWebImported(int n){if(web!=null)web.post(()->web.evaluateJavascript("window.nativePetImported && window.nativePetImported("+n+");",null));}
    private void openExternal(String url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Throwable e){toast("Could not open the video generator.");}
    }

    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    @Override protected void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}

    private static class ImageSource {
        final Uri uri; final String relative;
        ImageSource(Uri uri,String relative){this.uri=uri;this.relative=relative;}
    }

    public class NativeBridge {
        @JavascriptInterface public void startPet(){runOnUiThread(MainActivity.this::startPet);}
        @JavascriptInterface public void stopPet(){runOnUiThread(MainActivity.this::stopPet);}
        @JavascriptInterface public void grantOverlay(){runOnUiThread(MainActivity.this::requestOverlay);}
        @JavascriptInterface public void importPet(){runOnUiThread(MainActivity.this::openFolderPicker);}
        @JavascriptInterface public void importVideo(){runOnUiThread(MainActivity.this::openVideoPicker);}
        @JavascriptInterface public void importPetPack(){runOnUiThread(MainActivity.this::openZipPicker);}
        @JavascriptInterface public void resetPet(){runOnUiThread(MainActivity.this::resetToRemnant);}
        @JavascriptInterface public void openAiGenerator(){runOnUiThread(()->openExternal("https://upsampler.co/"));}
        @JavascriptInterface public void openNiftyVid(){runOnUiThread(()->openExternal("https://igorcsis.github.io/nifty-vid/"));}
        @JavascriptInterface public void chooseAiReference(){runOnUiThread(MainActivity.this::chooseAiReference);}
        @JavascriptInterface public void useBuiltInAiReference(){runOnUiThread(MainActivity.this::useBuiltInAiReference);}
        @JavascriptInterface public void chooseAiDriving(){runOnUiThread(MainActivity.this::chooseAiDriving);}
        @JavascriptInterface public void setAiWorkerUrl(String url){setAiWorkerUrl(url);}
        @JavascriptInterface public String getAiWorkerUrl(){return aiWorkerUrl();}
        @JavascriptInterface public void testAiWorker(){runOnUiThread(MainActivity.this::testAiWorker);}
        @JavascriptInterface public void renderAiVideo(String prompt){runOnUiThread(()->renderAiVideo(prompt));}
        @JavascriptInterface public boolean overlayReady(){return MainActivity.this.overlayReady();}
        @JavascriptInterface public boolean notificationsReady(){return MainActivity.this.notificationsReady();}
        @JavascriptInterface public void setSpeed(float value){
            float safe=Math.max(0.35f,Math.min(2.5f,value));
            prefs.edit().putFloat("speed",safe).apply();
            reloadRunningPet();
        }
        @JavascriptInterface public void setSize(int value){
            int safe=Math.max(110,Math.min(260,value));
            prefs.edit().putInt("size",safe).apply();
            reloadRunningPet();
        }
        @JavascriptInterface public String selectedPet(){return prefs.getString("selected_pet","remnant");}
        @JavascriptInterface public int importedCount(){return MainActivity.this.importedCount();}
    }
}
