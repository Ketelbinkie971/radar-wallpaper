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
import java.security.MessageDigest;
import java.util.*;

public class RadarWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new RadarEngine(); }

    private final class RadarEngine extends Engine implements LocationListener {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Map<String,Bitmap> cache=Collections.synchronizedMap(new LinkedHashMap<String,Bitmap>(50,.75f,true){
            protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){return size()>35;}
        });
        private HandlerThread thread; private Handler worker;
        private File radarCacheDir;
        private volatile Bitmap baseMap; private volatile boolean mapBusy;
        private String renderedMapTheme; private int renderedMapZoom=-1;
        private List<List<float[]>> geography;
        private int surfaceWidth,surfaceHeight;
        private boolean visible; private double lat=55.6761,lon=12.5683;
        private String radarHost,radarPath; private long lastMeta;
        private SharedPreferences prefs; private LocationManager locations;
        private final Runnable refresh=new Runnable(){
            @Override public void run(){
                try{
                    requestBaseMap();
                    drawFrame();
                    String previousPath=radarPath;
                    loadRadarMetadata();
                    if(radarPath!=null&&!radarPath.equals(previousPath))drawFrame();
                    trimDiskCache();
                }catch(Throwable ignored){drawFallback();}
                finally{if(visible&&worker!=null)worker.postDelayed(this,10*60_000L);}
            }
        };

        @Override public void onCreate(SurfaceHolder h){
            super.onCreate(h);prefs=getSharedPreferences("radar",MODE_PRIVATE);
            thread=new HandlerThread("radar-wallpaper");thread.start();worker=new Handler(thread.getLooper());
            locations=(LocationManager)getSystemService(LOCATION_SERVICE);
            radarCacheDir=new File(getCacheDir(),"radar-tiles");if(!radarCacheDir.exists())radarCacheDir.mkdirs();
            radarHost=prefs.getString("radar_host",null);radarPath=prefs.getString("radar_path",null);
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
        @Override public void onLocationChanged(Location l){if(l!=null){lat=l.getLatitude();lon=l.getLongitude();baseMap=null;requestBaseMap();drawFrame();}}
        @Override public void onProviderEnabled(String p){} @Override public void onProviderDisabled(String p){} @Override public void onStatusChanged(String p,int s,Bundle b){}

        private void loadRadarMetadata(){
            if(System.currentTimeMillis()-lastMeta<5*60_000L&&radarPath!=null)return;
            try{
                JSONObject root=new JSONObject(readText("https://api.rainviewer.com/public/weather-maps.json"));
                JSONArray frames=root.getJSONObject("radar").getJSONArray("past");
                radarHost=root.getString("host");radarPath=frames.getJSONObject(frames.length()-1).getString("path");lastMeta=System.currentTimeMillis();
                prefs.edit().putString("radar_host",radarHost).putString("radar_path",radarPath).apply();
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
                        Bitmap radar=getBitmap(radarHost+radarPath+"/256/"+z+"/"+nx+"/"+y+"/2/1_0.png",prefs.getString("palette","wu"));
                        if(radar!=null){paint.setAlpha((int)(255*prefs.getInt("opacity",72)/100f));c.drawBitmap(radar,null,new RectF(left,top,left+tile,top+tile),paint);}
                    }
                }
                paint.setColor(Color.rgb(226,244,249));paint.setAlpha(240);paint.setStyle(Paint.Style.FILL);c.drawCircle(w/2f,h/2f,5,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.rgb(22,51,65));c.drawCircle(w/2f,h/2f,9,paint);paint.setStyle(Paint.Style.FILL);
            }catch(Throwable ignored){}finally{if(c!=null)try{holder.unlockCanvasAndPost(c);}catch(Throwable ignored){}}
        }

        private void requestBaseMap(){
            String mapTheme=prefs.getString("map_theme","slate");int selectedZoom=prefs.getInt("zoom",6);
            if(mapBusy||surfaceWidth<1||surfaceHeight<1)return;
            if(baseMap!=null&&mapTheme.equals(renderedMapTheme)&&selectedZoom==renderedMapZoom)return;
            mapBusy=true;
            try{
                if(geography==null)geography=loadGeography();
                int width=Math.min(surfaceWidth,1600),height=Math.max(1,Math.round(width*(surfaceHeight/(float)surfaceWidth)));
                int zoom=selectedZoom;double targetLat=lat,targetLon=lon;int[] theme=mapThemeColours(mapTheme);
                Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
                Paint ocean=new Paint();ocean.setShader(new LinearGradient(0,0,0,height,theme[0],theme[1],Shader.TileMode.CLAMP));canvas.drawRect(0,0,width,height,ocean);
                double world=256.0*(1<<zoom),cx=worldX(targetLon,world),cy=worldY(targetLat,world);
                Paint land=new Paint(Paint.ANTI_ALIAS_FLAG);land.setStyle(Paint.Style.FILL);land.setShader(new LinearGradient(0,0,0,height,theme[2],theme[3],Shader.TileMode.CLAMP));
                Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(Math.max(1f,width/1100f));border.setColor(theme[4]);border.setAlpha(210);
                for(List<float[]> polygon:geography){
                    Path path=new Path();path.setFillType(Path.FillType.EVEN_ODD);
                    for(float[] ring:polygon){
                        int pointCount=ring.length/2;if(pointCount<3)continue;
                        double[] unwrappedX=new double[pointCount];
                        unwrappedX[0]=worldX(ring[0],world);double sumX=unwrappedX[0];
                        for(int point=1;point<pointCount;point++){
                            double x=worldX(ring[point*2],world),previous=unwrappedX[point-1];
                            while(x-previous>world/2)x-=world;
                            while(x-previous<-world/2)x+=world;
                            unwrappedX[point]=x;sumX+=x;
                        }
                        double wholeRingShift=Math.rint((cx-sumX/pointCount)/world)*world;
                        boolean started=false;
                        for(int point=0;point<pointCount;point++){
                            int i=point*2;double x=unwrappedX[point]+wholeRingShift;
                            float sx=(float)((x-cx)*width/surfaceWidth+width/2.0);
                            float sy=(float)((worldY(ring[i+1],world)-cy)*height/surfaceHeight+height/2.0);
                            if(!started){path.moveTo(sx,sy);started=true;}else path.lineTo(sx,sy);
                        }
                        path.close();
                    }
                    canvas.drawPath(path,land);canvas.drawPath(path,border);
                }
                baseMap=bitmap;renderedMapTheme=mapTheme;renderedMapZoom=zoom;
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
        private int[] mapThemeColours(String theme){
            if("navy".equals(theme))return new int[]{Color.rgb(4,15,25),Color.rgb(8,28,42),Color.rgb(25,43,55),Color.rgb(38,59,70),Color.rgb(104,135,149)};
            if("forest".equals(theme))return new int[]{Color.rgb(7,18,19),Color.rgb(13,29,28),Color.rgb(32,48,43),Color.rgb(44,61,52),Color.rgb(111,133,119)};
            if("plum".equals(theme))return new int[]{Color.rgb(16,12,23),Color.rgb(28,20,35),Color.rgb(48,38,53),Color.rgb(61,47,64),Color.rgb(137,116,143)};
            return new int[]{Color.rgb(7,18,25),Color.rgb(12,28,35),Color.rgb(34,49,56),Color.rgb(43,60,66),Color.rgb(103,125,134)};
        }

        private void drawFallback(){
            SurfaceHolder h=getSurfaceHolder();Canvas c=null;
            try{if(h==null||h.getSurface()==null||!h.getSurface().isValid())return;c=h.lockCanvas();if(c!=null)c.drawColor(Color.rgb(7,16,22));}
            catch(Throwable ignored){}finally{if(c!=null)try{h.unlockCanvasAndPost(c);}catch(Throwable ignored){}}
        }
        private Bitmap getBitmap(String url,String palette){
            String cacheKey="gradient-v1|"+url+"|"+palette;Bitmap hit=cache.get(cacheKey);if(hit!=null)return hit;
            try{
                File disk=new File(radarCacheDir,cacheName(cacheKey));
                if(disk.isFile()){
                    Bitmap saved=BitmapFactory.decodeFile(disk.getAbsolutePath());
                    if(saved!=null){cache.put(cacheKey,saved);return saved;}
                }
                HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();con.setConnectTimeout(8000);con.setReadTimeout(12000);
                con.setRequestProperty("User-Agent","RadarWallpaper/0.9 (personal live wallpaper)");
                Bitmap b;try(InputStream in=con.getInputStream()){b=BitmapFactory.decodeStream(in);}finally{con.disconnect();}
                if(b!=null&&!"blue".equals(palette))b=recolourRadar(b,palette);
                if(b!=null){
                    cache.put(cacheKey,b);
                    try(FileOutputStream out=new FileOutputStream(disk)){b.compress(Bitmap.CompressFormat.PNG,100,out);}
                }
                return b;
            }catch(Throwable e){return null;}
        }
        private String cacheName(String value)throws Exception{
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder name=new StringBuilder();
            for(byte b:bytes)name.append(String.format(Locale.US,"%02x",b));return name+".png";
        }
        private void trimDiskCache(){
            try{
                File[] files=radarCacheDir.listFiles();if(files==null||files.length<=80)return;
                Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
                for(int i=80;i<files.length;i++)files[i].delete();
            }catch(Throwable ignored){}
        }
        private Bitmap recolourRadar(Bitmap source,String palette){
            Bitmap out=source.copy(Bitmap.Config.ARGB_8888,true);int w=out.getWidth(),h=out.getHeight();
            int[] pixels=new int[w*h];out.getPixels(pixels,0,w,0,0,w,h);
            int[][] colours;
            if("wu_classic".equals(palette))colours=new int[][]{
                    {0,196,119},{0,163,92},{0,111,57},{255,188,0},
                    {255,68,0},{239,0,20},{224,0,126}};
            else if("night".equals(palette))colours=new int[][]{
                    {78,112,111},{55,139,132},{35,103,99},{184,145,76},
                    {188,88,70},{157,69,91},{119,72,119}};
            else colours=new int[][]{
                    {55,101,83},{40,128,91},{23,103,76},{190,154,52},
                    {194,74,48},{172,43,72},{142,48,105}};
            double[] levels={-5,10,20,35,45,55,65};
            for(int i=0;i<pixels.length;i++){
                int c=pixels[i],a=Color.alpha(c);if(a==0)continue;
                int r=Color.red(c),g=Color.green(c),b=Color.blue(c);double dbz;
                if(r>225&&g>225&&b>225){dbz=66;}
                else if(b>180&&r>180){dbz=g>175?66:55+(170-g)*9.0/92.0;}
                else if(r>220&&g>75&&b<80){dbz=35+(238-g)*9.0/109.0;}
                else if(r>80&&g<90&&b<80){dbz=45+(255-r)*9.0/162.0;}
                else if(b>g&&b>r&&g>=150){dbz=20-(g-163)*5.0/58.0;}
                else if(b>g&&b>r){dbz=20+(163-g)*14.0/92.0;}
                else if(r>70&&g>70&&b<180){dbz=Math.max(-5,15-(g-123)*.11);}
                else continue;
                int upper=1;
                while(upper<levels.length-1&&dbz>levels[upper])upper++;
                int lower=upper-1;double amount=Math.max(0,Math.min(1,(dbz-levels[lower])/(levels[upper]-levels[lower])));
                int nr=(int)Math.round(colours[lower][0]+(colours[upper][0]-colours[lower][0])*amount);
                int ng=(int)Math.round(colours[lower][1]+(colours[upper][1]-colours[lower][1])*amount);
                int nb=(int)Math.round(colours[lower][2]+(colours[upper][2]-colours[lower][2])*amount);
                pixels[i]=Color.argb(a,nr,ng,nb);
            }
            out.setPixels(pixels,0,w,0,0,w,h);return out;
        }
        private String readText(String url)throws Exception{
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()))){StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);return s.toString();}finally{c.disconnect();}
        }
    }
}
