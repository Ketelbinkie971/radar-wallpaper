package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.app.WallpaperColors;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public class RadarWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new RadarEngine(); }

    private final class RadarEngine extends Engine implements LocationListener {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Map<String, Bitmap> cache = Collections.synchronizedMap(new LinkedHashMap<String,Bitmap>(50,.75f,true) {
            protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e) { return size() > 35; }
        });
        private HandlerThread thread; private Handler worker;
        private File tileCacheDir;
        private boolean visible; private double lat = 55.6761, lon = 12.5683;
        private String radarHost, radarPath; private long lastMeta;
        private SharedPreferences prefs; private LocationManager locations;
        private final Runnable refresh = new Runnable() {
            @Override public void run() {
                try { loadRadarMetadata(); drawFrame(); }
                catch (Throwable ignored) { drawFallback(); }
                finally { if (visible && worker != null) worker.postDelayed(this, 10 * 60_000L); }
            }
        };

        @Override public void onCreate(SurfaceHolder h) {
            super.onCreate(h); prefs = getSharedPreferences("radar", MODE_PRIVATE);
            thread = new HandlerThread("radar-wallpaper"); thread.start(); worker = new Handler(thread.getLooper());
            locations = (LocationManager)getSystemService(LOCATION_SERVICE);
            tileCacheDir = new File(getCacheDir(), "map-tiles");
            if (!tileCacheDir.exists()) tileCacheDir.mkdirs();
        }
        @Override public void onDestroy() { stopLocation(); if(worker!=null) worker.removeCallbacksAndMessages(null); if(thread!=null) thread.quitSafely(); super.onDestroy(); }
        @Override public void onVisibilityChanged(boolean v) {
            visible = v; if(worker==null)return; worker.removeCallbacks(refresh);
            if (v) { startLocation(); worker.post(refresh); } else stopLocation();
        }
        @Override public void onSurfaceChanged(SurfaceHolder h, int f, int w, int he) { super.onSurfaceChanged(h,f,w,he); if(worker!=null) worker.post(this::drawFrame); }
        @Override public void onSurfaceDestroyed(SurfaceHolder h) { super.onSurfaceDestroyed(h); visible=false; stopLocation(); }
        @Override public WallpaperColors onComputeColors() { return new WallpaperColors(Color.valueOf(Color.rgb(11,15,18)), Color.valueOf(Color.rgb(85,140,170)), Color.valueOf(Color.rgb(210,230,238))); }

        private void startLocation() {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            try {
                Location l = locations.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (l != null) { lat=l.getLatitude(); lon=l.getLongitude(); }
                try { locations.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 15*60_000L, 20_000f, this, worker.getLooper()); } catch(Exception ignored) {}
                try { locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30*60_000L, 20_000f, this, worker.getLooper()); } catch(Exception ignored) {}
            } catch (Exception ignored) {}
        }
        private void stopLocation() { try { if(locations!=null) locations.removeUpdates(this); } catch(Exception ignored){} }
        @Override public void onLocationChanged(Location l) { if(l != null) { lat=l.getLatitude(); lon=l.getLongitude(); cache.clear(); drawFrame(); } }
        @Override public void onProviderEnabled(String p) {} @Override public void onProviderDisabled(String p) {} @Override public void onStatusChanged(String p,int s,Bundle b) {}

        private void loadRadarMetadata() {
            if (System.currentTimeMillis()-lastMeta < 5*60_000L && radarPath != null) return;
            try {
                String json = readText("https://api.rainviewer.com/public/weather-maps.json");
                JSONObject root = new JSONObject(json); JSONArray frames = root.getJSONObject("radar").getJSONArray("past");
                radarHost = root.getString("host"); radarPath = frames.getJSONObject(frames.length()-1).getString("path"); lastMeta=System.currentTimeMillis();
            } catch(Exception ignored) {}
        }

        private void drawFrame() {
            SurfaceHolder holder=getSurfaceHolder(); Canvas c=null;
            try {
                if(holder==null || holder.getSurface()==null || !holder.getSurface().isValid()) return;
                c=holder.lockCanvas(); if(c==null)return; c.drawColor(Color.rgb(8,13,17));
                int w=c.getWidth(), h=c.getHeight(), z=prefs.getInt("zoom",6), tile=256;
                double world=tile*(1<<z), cx=(lon+180.0)/360.0*world;
                double sin=Math.sin(Math.toRadians(Math.max(-85.05,Math.min(85.05,lat))));
                double cy=(.5-Math.log((1+sin)/(1-sin))/(4*Math.PI))*world;
                int minX=(int)Math.floor((cx-w/2.0)/tile), maxX=(int)Math.floor((cx+w/2.0)/tile);
                int minY=(int)Math.floor((cy-h/2.0)/tile), maxY=(int)Math.floor((cy+h/2.0)/tile);
                for(int y=minY;y<=maxY;y++) for(int x=minX;x<=maxX;x++) {
                    if(y < 0 || y >= (1<<z)) continue;
                    int nx=((x%(1<<z))+(1<<z))%(1<<z); float left=(float)(x*tile-(cx-w/2.0)), top=(float)(y*tile-(cy-h/2.0));
                    Bitmap base=getBitmap("https://tile.openstreetmap.org/"+z+"/"+nx+"/"+y+".png", true);
                    if(base!=null){
                        paint.setAlpha(255); paint.setColorFilter(darkMapFilter());
                        c.drawBitmap(base,null,new RectF(left,top,left+tile,top+tile),paint);
                        paint.setColorFilter(null);
                    }
                    if(radarHost!=null && radarPath!=null){ Bitmap radar=getBitmap(radarHost+radarPath+"/256/"+z+"/"+nx+"/"+y+"/2/1_0.png",false); if(radar!=null){ paint.setAlpha((int)(255*prefs.getInt("opacity",72)/100f)); c.drawBitmap(radar,null,new RectF(left,top,left+tile,top+tile),paint); } }
                }
                paint.setColor(Color.rgb(220,240,248)); paint.setAlpha(235); c.drawCircle(w/2f,h/2f,5,paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(Color.rgb(20,45,58)); c.drawCircle(w/2f,h/2f,9,paint); paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(20); paint.setColor(Color.argb(165,210,220,225)); c.drawText("RainViewer  •  © OpenStreetMap",18,h-24,paint);
            } catch(Throwable ignored) { } finally { if(c!=null) try { holder.unlockCanvasAndPost(c); } catch(Throwable ignored){} }
        }

        private ColorFilter darkMapFilter() {
            // Inverted low-contrast luminance: pale land becomes charcoal while dark labels stay visible.
            ColorMatrix m = new ColorMatrix(new float[]{
                    -.05f,-.10f,-.02f,0,66,
                    -.05f,-.11f,-.02f,0,74,
                    -.04f,-.10f,-.02f,0,80,
                    0,0,0,1,0});
            return new ColorMatrixColorFilter(m);
        }

        private void drawFallback() {
            SurfaceHolder h=getSurfaceHolder(); Canvas c=null;
            try { if(h==null || h.getSurface()==null || !h.getSurface().isValid())return; c=h.lockCanvas(); if(c!=null)c.drawColor(Color.rgb(8,13,17)); }
            catch(Throwable ignored){} finally { if(c!=null)try{h.unlockCanvasAndPost(c);}catch(Throwable ignored){} }
        }

        private Bitmap getBitmap(String url, boolean longCache) {
            Bitmap hit=cache.get(url); if(hit!=null)return hit;
            File disk = null;
            try {
                disk = longCache ? new File(tileCacheDir, cacheName(url)) : null;
                if(disk!=null && disk.isFile() && System.currentTimeMillis()-disk.lastModified() < 7L*24*60*60_000) {
                    Bitmap b=BitmapFactory.decodeFile(disk.getAbsolutePath()); if(b!=null){cache.put(url,b);return b;}
                }
                HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection(); con.setConnectTimeout(8000); con.setReadTimeout(12000);
                con.setRequestProperty("User-Agent","RadarWallpaper/0.2 (personal live wallpaper)");
                Bitmap b; try(InputStream in=con.getInputStream()){b=BitmapFactory.decodeStream(in);} finally {con.disconnect();}
                if(b!=null){ cache.put(url,b); if(disk!=null)try(FileOutputStream out=new FileOutputStream(disk)){b.compress(Bitmap.CompressFormat.PNG,100,out);} }
                return b;
            } catch(Throwable e){ return null; }
        }
        private String cacheName(String value) throws Exception {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8")); StringBuilder s=new StringBuilder();
            for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b)); return s+".png";
        }
        private String readText(String url) throws Exception { HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(8000); c.setReadTimeout(10000); try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()))){ StringBuilder s=new StringBuilder(); String line; while((line=r.readLine())!=null)s.append(line); return s.toString(); } }
    }
}
