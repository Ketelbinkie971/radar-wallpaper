package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.app.WallpaperColors;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.location.*;
import android.os.*;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class RadarWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new RadarEngine(); }

    private final class RadarEngine extends Engine implements LocationListener {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Map<String,Bitmap> cache=Collections.synchronizedMap(new LinkedHashMap<String,Bitmap>(50,.75f,true){
            protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){return size()>35;}
        });
        private HandlerThread thread; private Handler worker;
        private volatile Bitmap baseMap; private volatile boolean mapBusy;
        private List<List<float[]>> geography;
        private int surfaceWidth,surfaceHeight;
        private boolean visible; private double lat=55.6761,lon=12.5683;
        private String radarHost,radarPath; private long lastMeta;
        private SharedPreferences prefs; private LocationManager locations;
        private final Runnable refresh=new Runnable(){
            @Override public void run(){
                try{loadRadarMetadata();requestBaseMap();drawFrame();}catch(Throwable ignored){drawFallback();}
                finally{if(visible&&worker!=null)worker.postDelayed(this,10*60_000L);}
            }
        };

        @Override public void onCreate(SurfaceHolder h){
            super.onCreate(h);prefs=getSharedPreferences("radar",MODE_PRIVATE);
            thread=new HandlerThread("radar-wallpaper");thread.start();worker=new Handler(thread.getLooper());
            locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        }
        @Override public void onDestroy(){stopLocation();if(worker!=null)worker.removeCallbacksAndMessages(null);if(thread!=null)thread.quitSafely();super.onDestroy();}
        @Override public void onVisibilityChanged(boolean v){
            visible=v;if(worker==null)return;worker.removeCallbacks(refresh);
            if(v){startLocation();worker.post(refresh);}else stopLocation();
        }
        @Override public void onSurfaceChanged(SurfaceHolder h,int f,int w,int he){
            super.onSurfaceChanged(h,f,w,he);surfaceWidth=w;surfaceHeight=he;baseMap=null;
            if(worker!=null)worker.post(()->{requestBaseMap();drawFrame();});
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder h){super.onSurfaceDestroyed(h);visible=false;stopLocation();}
        @Override public WallpaperColors onComputeColors(){return new WallpaperColors(Color.valueOf(Color.rgb(9,18,24)),Color.valueOf(Color.rgb(78,114,128)),Color.valueOf(Color.rgb(215,235,242)));}

        private void startLocation(){
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
            try{
                Location l=locations.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if(l!=null){lat=l.getLatitude();lon=l.getLongitude();}
                try{locations.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,15*60_000L,20_000f,this,worker.getLooper());}catch(Exception ignored){}
                try{locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,30*60_000L,20_000f,this,worker.getLooper());}catch(Exception ignored){}
            }catch(Exception ignored){}
        }
        private void stopLocation(){try{if(locations!=null)locations.removeUpdates(this);}catch(Exception ignored){}}
        @Override public void onLocationChanged(Location l){if(l!=null){lat=l.getLatitude();lon=l.getLongitude();cache.clear();baseMap=null;requestBaseMap();drawFrame();}}
        @Override public void onProviderEnabled(String p){} @Override public void onProviderDisabled(String p){} @Override public void onStatusChanged(String p,int s,Bundle b){}

        private void loadRadarMetadata(){
            if(System.currentTimeMillis()-lastMeta<5*60_000L&&radarPath!=null)return;
            try{
                JSONObject root=new JSONObject(readText("https://api.rainviewer.com/public/weather-maps.json"));
                JSONArray frames=root.getJSONObject("radar").getJSONArray("past");
                radarHost=root.getString("host");radarPath=frames.getJSONObject(frames.length()-1).getString("path");lastMeta=System.currentTimeMillis();
            }catch(Exception ignored){}
        }

        private void drawFrame(){
            SurfaceHolder holder=getSurfaceHolder();Canvas c=null;
            try{
                if(holder==null||holder.getSurface()==null||!holder.getSurface().isValid())return;
                c=holder.lockCanvas();if(c==null)return;c.drawColor(Color.rgb(7,16,22));
                int w=c.getWidth(),h=c.getHeight(),z=prefs.getInt("zoom",6),tile=256;
                Bitmap map=baseMap;if(map!=null){paint.setAlpha(255);c.drawBitmap(map,null,new Rect(0,0,w,h),paint);}
                double world=tile*(1<<z),cx=worldX(lon,world),cy=worldY(lat,world);
                int minX=(int)Math.floor((cx-w/2.0)/tile),maxX=(int)Math.floor((cx+w/2.0)/tile);
                int minY=(int)Math.floor((cy-h/2.0)/tile),maxY=(int)Math.floor((cy+h/2.0)/tile);
                for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++){
                    if(y<0||y>=(1<<z))continue;
                    int nx=((x%(1<<z))+(1<<z))%(1<<z);
                    float left=(float)(x*tile-(cx-w/2.0)),top=(float)(y*tile-(cy-h/2.0));
                    if(radarHost!=null&&radarPath!=null){
                        Bitmap radar=getBitmap(radarHost+radarPath+"/256/"+z+"/"+nx+"/"+y+"/2/1_0.png");
                        if(radar!=null){paint.setAlpha((int)(255*prefs.getInt("opacity",72)/100f));c.drawBitmap(radar,null,new RectF(left,top,left+tile,top+tile),paint);}
                    }
                }
                paint.setColor(Color.rgb(226,244,249));paint.setAlpha(240);paint.setStyle(Paint.Style.FILL);c.drawCircle(w/2f,h/2f,5,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.rgb(22,51,65));c.drawCircle(w/2f,h/2f,9,paint);paint.setStyle(Paint.Style.FILL);
            }catch(Throwable ignored){}finally{if(c!=null)try{holder.unlockCanvasAndPost(c);}catch(Throwable ignored){}}
        }

        private void requestBaseMap(){
            if(mapBusy||surfaceWidth<1||surfaceHeight<1||baseMap!=null)return;mapBusy=true;
            try{
                if(geography==null)geography=loadGeography();
                int width=Math.min(surfaceWidth,1600),height=Math.max(1,Math.round(width*(surfaceHeight/(float)surfaceWidth)));
                int zoom=prefs.getInt("zoom",6);double targetLat=lat,targetLon=lon;
                Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.rgb(7,18,25));
                double world=256.0*(1<<zoom),cx=worldX(targetLon,world),cy=worldY(targetLat,world);
                Paint land=new Paint(Paint.ANTI_ALIAS_FLAG);land.setStyle(Paint.Style.FILL);land.setColor(Color.rgb(37,53,60));
                Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(Math.max(1f,width/1100f));border.setColor(Color.rgb(103,125,134));border.setAlpha(210);
                for(List<float[]> polygon:geography){
                    Path path=new Path();path.setFillType(Path.FillType.EVEN_ODD);
                    for(float[] ring:polygon){
                        boolean started=false;
                        for(int i=0;i<ring.length;i+=2){
                            double x=worldX(ring[i],world);while(x-cx>world/2)x-=world;while(x-cx<-world/2)x+=world;
                            float sx=(float)((x-cx)*width/surfaceWidth+width/2.0);
                            float sy=(float)((worldY(ring[i+1],world)-cy)*height/surfaceHeight+height/2.0);
                            if(!started){path.moveTo(sx,sy);started=true;}else path.lineTo(sx,sy);
                        }
                        path.close();
                    }
                    canvas.drawPath(path,land);canvas.drawPath(path,border);
                }
                baseMap=bitmap;
            }catch(Throwable ignored){baseMap=null;}finally{mapBusy=false;drawFrame();}
        }

        private List<List<float[]>> loadGeography()throws Exception{
            List<List<float[]>> result=new ArrayList<>();StringBuilder text=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(getResources().openRawResource(R.raw.natural_earth_countries)))){
                String line;while((line=reader.readLine())!=null)text.append(line);
            }
            JSONArray polygons=new JSONArray(text.toString());
            for(int p=0;p<polygons.length();p++){
                JSONArray ringsJson=polygons.getJSONArray(p);List<float[]> rings=new ArrayList<>();
                for(int r=0;r<ringsJson.length();r++){
                    JSONArray points=ringsJson.getJSONArray(r);float[] ring=new float[points.length()*2];
                    for(int i=0;i<points.length();i++){JSONArray point=points.getJSONArray(i);ring[i*2]=(float)point.getDouble(0);ring[i*2+1]=(float)point.getDouble(1);}
                    rings.add(ring);
                }
                result.add(rings);
            }
            return result;
        }

        private double worldX(double longitude,double world){return(longitude+180.0)/360.0*world;}
        private double worldY(double latitude,double world){double s=Math.sin(Math.toRadians(Math.max(-85.05,Math.min(85.05,latitude))));return(.5-Math.log((1+s)/(1-s))/(4*Math.PI))*world;}

        private void drawFallback(){
            SurfaceHolder h=getSurfaceHolder();Canvas c=null;
            try{if(h==null||h.getSurface()==null||!h.getSurface().isValid())return;c=h.lockCanvas();if(c!=null)c.drawColor(Color.rgb(7,16,22));}
            catch(Throwable ignored){}finally{if(c!=null)try{h.unlockCanvasAndPost(c);}catch(Throwable ignored){}}
        }
        private Bitmap getBitmap(String url){
            Bitmap hit=cache.get(url);if(hit!=null)return hit;
            try{
                HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();con.setConnectTimeout(8000);con.setReadTimeout(12000);
                con.setRequestProperty("User-Agent","RadarWallpaper/0.4 (personal live wallpaper)");
                Bitmap b;try(InputStream in=con.getInputStream()){b=BitmapFactory.decodeStream(in);}finally{con.disconnect();}
                if(b!=null)cache.put(url,b);return b;
            }catch(Throwable e){return null;}
        }
        private String readText(String url)throws Exception{
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()))){StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);return s.toString();}finally{c.disconnect();}
        }
    }
}

