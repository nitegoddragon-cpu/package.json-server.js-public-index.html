package com.hollowhaven.remnant;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AiVideoWorkerClient {
    interface Callback { void done(boolean ok, String message, String value); }
    private static final String WS_API = "https://api.wavespeed.ai/api/v3";
    private final Context context;
    AiVideoWorkerClient(Context context) { this.context = context.getApplicationContext(); }

    private boolean isWaveSpeed(String baseUrl) { return baseUrl != null && baseUrl.trim().startsWith("wavespeed://"); }
    private String waveSpeedKey(String baseUrl) {
        String x = baseUrl == null ? "" : baseUrl.trim().substring("wavespeed://".length()).trim();
        if (x.isEmpty()) throw new IllegalArgumentException("Enter your WaveSpeedAI API key.");
        return x;
    }
    private HttpURLConnection openWave(String url, String method, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(15000); c.setReadTimeout(60000);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("Authorization","Bearer "+key);
        return c;
    }

    void health(String baseUrl, Callback cb) { new Thread(() -> {
        try {
            if (isWaveSpeed(baseUrl)) {
                String key = waveSpeedKey(baseUrl);
                HttpURLConnection c = openWave(WS_API+"/predictions","POST",key);
                c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()) {
                    out.write("{\"page\":1,\"page_size\":1}".getBytes(StandardCharsets.UTF_8));
                }
                int code=c.getResponseCode(); String body=read(code>=400?c.getErrorStream():c.getInputStream());
                if(code/100!=2) throw new IOException("WaveSpeed HTTP "+code+": "+body);
                cb.done(true,"WaveSpeedAI API key accepted.","ok"); return;
            }
            String url=normalize(baseUrl)+"/health";
            HttpURLConnection c=open(url,"GET"); String body=read(c.getInputStream());
            if(c.getResponseCode()/100!=2) throw new IOException("HTTP "+c.getResponseCode());
            cb.done(true,body,body);
        } catch(Throwable e){cb.done(false,e.getMessage()==null?"AI service health check failed":e.getMessage(),"");}
    },"wan-health").start(); }

    void render(String baseUrl, Uri reference, Uri driving, String prompt, int steps, Callback cb) { new Thread(() -> {
        try {
            if (isWaveSpeed(baseUrl)) { renderWaveSpeed(waveSpeedKey(baseUrl),reference,driving,prompt,cb); return; }
            String boundary="----Hollowhaven"+UUID.randomUUID();
            URL u=new URL(normalize(baseUrl)+"/render"); HttpURLConnection c=(HttpURLConnection)u.openConnection();
            c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(20000);c.setReadTimeout(120000);
            c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            try(OutputStream out=new BufferedOutputStream(c.getOutputStream())){
                writeFilePart(out,boundary,"reference_image","reference.png","image/png",reference);
                writeFilePart(out,boundary,"driving_video","driving.mp4","video/mp4",driving);
                writeTextPart(out,boundary,"prompt",prompt==null?"":prompt);
                writeTextPart(out,boundary,"steps",Integer.toString(steps));
                out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code=c.getResponseCode();String body=read(code>=400?c.getErrorStream():c.getInputStream());
            if(code/100!=2)throw new IOException("Worker HTTP "+code+": "+body);
            String job=json(body,"job_id");if(job==null)throw new IOException("Worker did not return a job id.");
            cb.done(true,"queued",job);
        }catch(Throwable e){cb.done(false,e.getMessage()==null?"Render request failed":e.getMessage(),"");}
    },"wan-render").start(); }

    private void renderWaveSpeed(String key, Uri reference, Uri driving, String prompt, Callback cb) throws Exception {
        String imageUrl=uploadWave(key,reference,"remnant-reference.png","image/png");
        String videoUrl=uploadWave(key,driving,"remnant-driving.mp4","video/mp4");
        JSONObject body=new JSONObject();
        body.put("image",imageUrl); body.put("video",videoUrl); body.put("resolution","480p");
        if(prompt!=null&&!prompt.trim().isEmpty()) body.put("prompt",prompt);
        body.put("motion_prompt","Animate the Remnant naturally using the driving video motion. Preserve the exact creature identity, anatomy, proportions, colors, face, vines, leaves, feathers and glowing core.");
        HttpURLConnection c=openWave(WS_API+"/wavespeed-ai/wan-2.2/animate-2","POST",key);
        c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
        try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();String response=read(code>=400?c.getErrorStream():c.getInputStream());
        if(code/100!=2)throw new IOException("WaveSpeed render HTTP "+code+": "+response);
        JSONObject root=new JSONObject(response);JSONObject data=root.optJSONObject("data");if(data==null)data=root;
        String id=data.optString("id","");if(id.isEmpty())throw new IOException("WaveSpeed did not return a prediction id.");
        cb.done(true,"queued",id);
    }

    private String uploadWave(String key, Uri uri, String filename, String mime) throws Exception {
        long size=contentLength(uri); if(size<=0)throw new IOException("Could not determine input size.");
        if(size>200L*1024L*1024L)throw new IOException("Input exceeds 200 MB.");
        JSONObject ticketBody=new JSONObject().put("filename",filename).put("size",size).put("content_type",mime);
        HttpURLConnection c=openWave(WS_API+"/media/uploads","POST",key);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
        try(OutputStream out=c.getOutputStream()){out.write(ticketBody.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();String body=read(code>=400?c.getErrorStream():c.getInputStream());
        if(code/100!=2)throw new IOException("WaveSpeed upload ticket HTTP "+code+": "+body);
        JSONObject data=new JSONObject(body).getJSONObject("data");JSONObject upload=data.getJSONObject("upload");
        String url=upload.getString("url");JSONObject headers=upload.optJSONObject("headers");
        HttpURLConnection put=(HttpURLConnection)new URL(url).openConnection();put.setRequestMethod("PUT");put.setDoOutput(true);put.setConnectTimeout(20000);put.setReadTimeout(300000);
        if(headers!=null){java.util.Iterator<String> it=headers.keys();while(it.hasNext()){String k=it.next();put.setRequestProperty(k,headers.getString(k));}}
        try(OutputStream out=new BufferedOutputStream(put.getOutputStream());InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("Could not open selected input.");
            byte[] b=new byte[64*1024];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>200L*1024L*1024L)throw new IOException("Input exceeds 200 MB.");out.write(b,0,n);}
        }
        int pc=put.getResponseCode();if(pc/100!=2)throw new IOException("WaveSpeed media upload HTTP "+pc);
        return data.getString("download_url");
    }

    void pollUntilDone(String baseUrl,String jobId,Callback cb){new Thread(() -> {
        try {
            if(isWaveSpeed(baseUrl)){String key=waveSpeedKey(baseUrl);String url=WS_API+"/predictions/"+URLEncoder.encode(jobId,"UTF-8")+"/result";
                int delay=2500;
                for(int i=0;i<720;i++){HttpURLConnection c=null; int code=0; String body=""; try { c=openWave(url,"GET",key); code=c.getResponseCode(); body=read(code>=400?c.getErrorStream():c.getInputStream()); } catch(IOException transientError) { Thread.sleep(Math.min(10000,delay)); delay=Math.min(10000,delay+500); continue; }
                    if(code==429||code==500||code==502||code==503||code==504){Thread.sleep(Math.min(10000,delay)); delay=Math.min(10000,delay+500); continue;}
                    if(code/100!=2)throw new IOException("WaveSpeed result HTTP "+code+": "+body);
                    JSONObject root=new JSONObject(body);JSONObject data=root.optJSONObject("data");if(data==null)data=root;String status=data.optString("status","processing");
                    if("completed".equalsIgnoreCase(status)){JSONArray outs=data.optJSONArray("outputs");if(outs==null||outs.length()==0)throw new IOException("WaveSpeed completed without output.");String result=outs.optString(0,"");if(result.isEmpty())throw new IOException("WaveSpeed returned an empty output.");cb.done(true,"completed",result);return;}
                    if("failed".equalsIgnoreCase(status)||"cancelled".equalsIgnoreCase(status)||"timeout".equalsIgnoreCase(status)||"deleted".equalsIgnoreCase(status))throw new IOException(data.optString("error",status));
                    cb.done(true,"progress",status);Thread.sleep(delay);delay=Math.min(10000,delay+500);
                } throw new IOException("WaveSpeed job polling exceeded 60 minutes.");
            }
            String url=normalize(baseUrl)+"/jobs/"+URLEncoder.encode(jobId,"UTF-8");
            for(int i=0;i<360;i++){HttpURLConnection c=open(url,"GET");int code=c.getResponseCode();String body=read(code>=400?c.getErrorStream():c.getInputStream());if(code/100!=2)throw new IOException("Job status HTTP "+code+": "+body);
                String status=json(body,"status");if("completed".equalsIgnoreCase(status)){cb.done(true,"completed",normalize(baseUrl)+"/jobs/"+jobId+"/result");return;}if("failed".equalsIgnoreCase(status))throw new IOException(json(body,"error"));cb.done(true,"progress",status==null?"queued":status);Thread.sleep(5000);}
            throw new IOException("Worker job timed out after 30 minutes.");
        }catch(Throwable e){cb.done(false,e.getMessage()==null?"Job polling failed":e.getMessage(),"");}
    },"wan-poll").start();}

    File downloadResult(String url)throws Exception{HttpURLConnection c=open(url,"GET");if(c.getResponseCode()/100!=2)throw new IOException("Result download HTTP "+c.getResponseCode());return downloadStream(c.getInputStream());}
    private File downloadStream(InputStream in)throws Exception{File out=new File(context.getCacheDir(),"remnant-ai-"+System.currentTimeMillis()+".mp4");try(InputStream x=in;OutputStream os=new BufferedOutputStream(new FileOutputStream(out))){byte[]b=new byte[64*1024];int n;long total=0;while((n=x.read(b))!=-1){total+=n;if(total>200L*1024L*1024L)throw new IOException("Result exceeds 200 MB.");os.write(b,0,n);}}return out;}
    private long contentLength(Uri uri)throws Exception{android.database.Cursor q=context.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.SIZE},null,null,null);if(q!=null)try{if(q.moveToFirst()){int i=q.getColumnIndex(android.provider.OpenableColumns.SIZE);if(i>=0&&!q.isNull(i))return q.getLong(i);}}finally{q.close();}return -1;}
    private HttpURLConnection open(String url,String method)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Accept","application/json");return c;}
    private static String normalize(String u){String x=u==null?"":u.trim();while(x.endsWith("/"))x=x.substring(0,x.length()-1);if(!x.startsWith("https://")&&!x.startsWith("http://"))throw new IllegalArgumentException("Worker URL must start with http:// or https://, or use wavespeed://API_KEY.");return x;}
    private static void writeTextPart(OutputStream out,String b,String name,String value)throws IOException{out.write(("--"+b+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));}
    private void writeFilePart(OutputStream out,String b,String field,String filename,String mime,Uri uri)throws IOException{out.write(("--"+b+"\r\nContent-Disposition: form-data; name=\""+field+"\"; filename=\""+filename+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Could not open selected file");byte[]x=new byte[64*1024];int n;long total=0;while((n=in.read(x))!=-1){total+=n;if(total>200L*1024L*1024L)throw new IOException("Input exceeds 200 MB.");out.write(x,0,n);}}out.write("\r\n".getBytes(StandardCharsets.UTF_8));}
    private static String read(InputStream in)throws IOException{if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=x.read(b))!=-1)o.write(b,0,n);return o.toString(StandardCharsets.UTF_8);}}
    private static String json(String body,String key){Matcher m=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(body==null?"":body);return m.find()?m.group(1):null;}
}