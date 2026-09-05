package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.app.WallpaperColors;
import android.content.*;
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
    static final String ACTION_REFRESH_TRACKS="com.ketelcustoms.radarwallpaper.REFRESH_TRACKS";
    @Override public Engine onCreateEngine() { return new RadarEngine(); }

    private final class RadarEngine extends Engine implements LocationListener {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Map<String,Bitmap> cache=Collections.synchronizedMap(new LinkedHashMap<String,Bitmap>(50,.75f,true){
            protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){return size()>35;}
        });
        private HandlerThread thread; private Handler worker;
        private File radarCacheDir;
        private volatile Bitmap baseMap,lastFrame,lastFrameWithoutFlights; private volatile boolean mapBusy;
        private String renderedMapTheme; private int renderedMapZoom=-1;
        private List<List<float[]>> geography;
        private int surfaceWidth,surfaceHeight;
        private volatile boolean visible; private double lat=55.6761,lon=12.5683;
        private String radarHost,radarPath; private long lastMeta;
        private List<FlightCalendar.Leg> flightLegs=Collections.emptyList(); private long lastFlightLoad;
        private SharedPreferences prefs; private LocationManager locations;
        private FlightCalendar.Leg animatedArrival;private List<FlightCalendar.Leg> arrivalSequence=Collections.emptyList();private int arrivalIndex,unlockChecks;private long animationStarted;private boolean receiverRegistered,animateWholeArrival;private volatile boolean unlockPending,pathsHiddenForUnlock;private Bitmap animationFrame,animationBase;
        private final BroadcastReceiver unlockReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){unlockPending=true;pathsHiddenForUnlock=true;unlockChecks=0;if(worker!=null){worker.removeCallbacks(unlockAnimation);worker.post(()->{stopArrivalAnimation(false);showCleanFrame();});}}else if(Intent.ACTION_USER_PRESENT.equals(intent.getAction())){unlockPending=true;scheduleUnlockAnimation();}else if(ACTION_REFRESH_TRACKS.equals(intent.getAction())&&worker!=null)worker.post(()->{lastFlightLoad=0;loadFlightTrails();drawFrame();});}};
        private final Runnable unlockAnimation=new Runnable(){@Override public void run(){if(!visible||!unlockPending)return;android.app.KeyguardManager keyguard=(android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE);if(keyguard!=null&&keyguard.isKeyguardLocked()){if(unlockChecks++<80&&worker!=null)worker.postDelayed(this,250);return;}unlockPending=false;unlockChecks=0;if(!startArrivalAnimation())restoreHiddenPaths();}};
        private final Runnable nextArrival=this::startNextArrival;
        private final Runnable arrivalAnimation=new Runnable(){@Override public void run(){if(worker==null||!visible||animatedArrival==null){stopArrivalAnimation(false);return;}float progress=Math.min(1f,(SystemClock.uptimeMillis()-animationStarted)/5500f);drawArrivalFrame(progress);if(progress<1f)worker.postDelayed(this,50);else if(arrivalIndex+1<arrivalSequence.size()){commitAnimatedArrival();animatedArrival=null;arrivalIndex++;worker.postDelayed(nextArrival,1000);}else stopArrivalAnimation(true);}};
        private final Runnable refresh=new Runnable(){
            @Override public void run(){
                try{
                    loadFlightTrails();
                    requestBaseMap();
                    drawFrame();
                    String previousPath=radarPath;
                    loadRadarMetadata();
                    if(radarPath!=null&&!radarPath.equals(previousPath))drawFrame();
                    trimDiskCache();
                    if(OpenSkyTracks.downloadMissing(getApplicationContext(),prefs,flightLegs,System.currentTimeMillis()))drawFrame();
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
            try{IntentFilter filter=new IntentFilter(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(ACTION_REFRESH_TRACKS);if(Build.VERSION.SDK_INT>=33)RadarWallpaperService.this.registerReceiver(unlockReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else RadarWallpaperService.this.registerReceiver(unlockReceiver,filter);receiverRegistered=true;}catch(Exception ignored){}
        }
        @Override public void onDestroy(){
            stopLocation();if(receiverRegistered)try{RadarWallpaperService.this.unregisterReceiver(unlockReceiver);}catch(Exception ignored){}if(worker!=null)worker.removeCallbacksAndMessages(null);if(thread!=null)thread.quitSafely();
            cache.clear();recycle(animationFrame);recycle(animationBase);recycle(lastFrameWithoutFlights);animationFrame=null;animationBase=null;lastFrameWithoutFlights=null;baseMap=null;lastFrame=null;geography=null;super.onDestroy();
        }
        @Override public void onVisibilityChanged(boolean v){
            visible=v;if(worker==null)return;worker.removeCallbacks(refresh);
            if(v){startLocation();if(pathsHiddenForUnlock)showCleanFrame();scheduleUnlockAnimation();worker.postDelayed(refresh,1500);}else{worker.removeCallbacks(unlockAnimation);stopArrivalAnimation(false);stopLocation();}
        }
        @Override public void onSurfaceChanged(SurfaceHolder h,int f,int w,int he){
            super.onSurfaceChanged(h,f,w,he);surfaceWidth=w;surfaceHeight=he;baseMap=null;
            if(worker!=null)worker.post(()->{requestBaseMap();drawFrame();});
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder h){super.onSurfaceDestroyed(h);visible=false;stopLocation();}
        @Override public WallpaperColors onComputeColors(){return new WallpaperColors(Color.valueOf(Color.rgb(9,18,24)),Color.valueOf(Color.rgb(78,114,128)),Color.valueOf(Color.rgb(215,235,242)));}

        private void startLocation(){
            if(isPreview())return;
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
            Bitmap frame=null;
            try{
                int viewW=surfaceWidth,viewH=surfaceHeight;if(viewW<1||viewH<1)return;
                int w=Math.min(viewW,900),h=Math.max(1,Math.round(w*(viewH/(float)viewW)));
                if(isPreview()){
                    frame=TaiwanPreviewRenderer.render(getApplicationContext(),w,h,prefs.getInt("zoom",6),prefs.getString("map_theme","slate"),prefs.getString("palette","night"),prefs.getInt("opacity",72));
                    FlightCalendar.draw(new Canvas(frame),flightLegs,25.0777,121.2330,prefs.getInt("zoom",6),w,h,viewW,viewH,prefs);
                    postFrame(frame);Bitmap old=lastFrame;lastFrame=frame;frame=null;if(old!=null&&!old.isRecycled())old.recycle();return;
                }
                double scale=w/(double)viewW;
                frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(frame);c.drawColor(Color.rgb(7,16,22));
                int z=prefs.getInt("zoom",6),tile=256;
                Bitmap map=baseMap;if(map!=null){paint.setAlpha(255);c.drawBitmap(map,null,new Rect(0,0,w,h),paint);}
                if(lastFrame==null)postFrame(frame);
                double world=tile*(1<<z),cx=worldX(lon,world),cy=worldY(lat,world);
                int minX=(int)Math.floor((cx-viewW/2.0)/tile),maxX=(int)Math.floor((cx+viewW/2.0)/tile);
                int minY=(int)Math.floor((cy-viewH/2.0)/tile),maxY=(int)Math.floor((cy+viewH/2.0)/tile);
                for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++){
                    if(y<0||y>=(1<<z))continue;
                    int nx=((x%(1<<z))+(1<<z))%(1<<z);
                    float left=(float)((x*tile-(cx-viewW/2.0))*scale),top=(float)((y*tile-(cy-viewH/2.0))*scale),drawTile=(float)(tile*scale);
                    if(radarHost!=null&&radarPath!=null){
                        Bitmap radar=getBitmap(radarHost+radarPath+"/256/"+z+"/"+nx+"/"+y+"/2/1_0.png",prefs.getString("palette","night"));
                        if(radar!=null){paint.setAlpha((int)(255*prefs.getInt("opacity",72)/100f));c.drawBitmap(radar,null,new RectF(left,top,left+drawTile,top+drawTile),paint);}
                    }
                }
                Bitmap clean=frame.copy(Bitmap.Config.ARGB_8888,true);
                drawLocationMarker(new Canvas(clean),w,h);
                FlightCalendar.draw(c,flightLegs,lat,lon,z,w,h,viewW,viewH,prefs);
                drawLocationMarker(c,w,h);
                if(animationBase==null&&!pathsHiddenForUnlock)postFrame(frame);Bitmap old=lastFrame;lastFrame=frame;frame=null;
                Bitmap oldClean=lastFrameWithoutFlights;lastFrameWithoutFlights=clean;
                if(old!=null&&old!=baseMap&&!old.isRecycled())old.recycle();
                recycle(oldClean);
            }catch(Throwable ignored){}finally{if(frame!=null&&!frame.isRecycled())frame.recycle();}
        }

        private void drawLocationMarker(Canvas canvas,int width,int height){
            paint.setColor(Color.rgb(226,244,249));paint.setAlpha(240);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(width/2f,height/2f,5,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.rgb(22,51,65));canvas.drawCircle(width/2f,height/2f,9,paint);paint.setStyle(Paint.Style.FILL);
        }

        private void postFrame(Bitmap frame){
            SurfaceHolder holder=getSurfaceHolder();Canvas surface=null;
            try{
                if(frame==null||holder==null||holder.getSurface()==null||!holder.getSurface().isValid())return;
                surface=holder.lockCanvas();if(surface==null)return;
                paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);
                surface.drawBitmap(frame,null,new Rect(0,0,surface.getWidth(),surface.getHeight()),paint);
            }catch(Throwable ignored){}finally{if(surface!=null)try{holder.unlockCanvasAndPost(surface);}catch(Throwable ignored){}}
        }

        private void requestBaseMap(){
            if(isPreview())return;
            String mapTheme=prefs.getString("map_theme","slate");int[] selectedTheme=mapThemeColours(mapTheme);String mapSignature=mapTheme+"|"+Arrays.hashCode(selectedTheme);int selectedZoom=prefs.getInt("zoom",6);
            if(mapBusy||surfaceWidth<1||surfaceHeight<1)return;
            if(baseMap!=null&&mapSignature.equals(renderedMapTheme)&&selectedZoom==renderedMapZoom)return;
            mapBusy=true;
            try{
                if(geography==null)geography=loadGeography();
                int width=Math.min(surfaceWidth,900),height=Math.max(1,Math.round(width*(surfaceHeight/(float)surfaceWidth)));
                int zoom=selectedZoom;double targetLat=lat,targetLon=lon;int[] theme=selectedTheme;
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
                baseMap=bitmap;renderedMapTheme=mapSignature;renderedMapZoom=zoom;
            }catch(Throwable ignored){baseMap=null;}finally{mapBusy=false;}
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
            return PresetStore.colours(getApplicationContext(),PresetStore.MAP,theme);
        }

        private void loadFlightTrails(){
            long now=System.currentTimeMillis();if(now-lastFlightLoad<5*60_000L)return;
            flightLegs=FlightCalendar.load(getApplicationContext(),prefs,now);OpenSkyTracks.attachCached(getApplicationContext(),flightLegs);lastFlightLoad=now;
        }

        private boolean startArrivalAnimation(){
            if(isPreview()||!visible||!prefs.getBoolean("animate_arrival_unlock",true)||!prefs.getBoolean("flight_trails",false))return false;loadFlightTrails();long now=System.currentTimeMillis();FlightCalendar.Leg latest=null;for(FlightCalendar.Leg leg:flightLegs)if(leg.end<=now&&(latest==null||leg.end>latest.end))latest=leg;if(latest==null)return false;ArrayList<FlightCalendar.Leg> sequence=new ArrayList<>();for(FlightCalendar.Leg leg:flightLegs)if(leg.end<=now&&(sameLocalDay(leg.start,latest.end)||sameLocalDay(leg.end,latest.end)))sequence.add(leg);sequence.sort(Comparator.comparingLong(leg->leg.start));if(sequence.isEmpty())return false;if(lastFrameWithoutFlights==null)drawFrame();Bitmap clean=lastFrameWithoutFlights;if(clean==null||clean.isRecycled())return false;worker.removeCallbacks(refresh);stopArrivalAnimation(false);arrivalSequence=sequence;arrivalIndex=0;animationBase=clean.copy(Bitmap.Config.ARGB_8888,true);animationFrame=Bitmap.createBitmap(clean.getWidth(),clean.getHeight(),Bitmap.Config.ARGB_8888);postFrame(animationBase);startNextArrival();return true;
        }

        private void scheduleUnlockAnimation(){if(worker==null||!visible||!unlockPending)return;worker.removeCallbacks(unlockAnimation);worker.postDelayed(unlockAnimation,180);}

        private void startNextArrival(){if(!visible||arrivalIndex>=arrivalSequence.size()||animationFrame==null){stopArrivalAnimation(false);return;}animatedArrival=arrivalSequence.get(arrivalIndex);animateWholeArrival=arrivalSequence.size()>1&&arrivalIndex==arrivalSequence.size()-1&&FlightCalendar.routeFits(animatedArrival,lat,lon,prefs.getInt("zoom",6),animationFrame.getWidth(),animationFrame.getHeight(),surfaceWidth,surfaceHeight);animationStarted=SystemClock.uptimeMillis();worker.postDelayed(arrivalAnimation,300);}

        private boolean sameLocalDay(long first,long second){Calendar a=Calendar.getInstance(),b=Calendar.getInstance();a.setTimeInMillis(first);b.setTimeInMillis(second);return a.get(Calendar.ERA)==b.get(Calendar.ERA)&&a.get(Calendar.YEAR)==b.get(Calendar.YEAR)&&a.get(Calendar.DAY_OF_YEAR)==b.get(Calendar.DAY_OF_YEAR);}

        private void drawArrivalFrame(float progress){
            Bitmap source=animationBase,frame=animationFrame;try{if(source==null||source.isRecycled()||frame==null||frame.isRecycled())return;Canvas canvas=new Canvas(frame);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);canvas.drawBitmap(source,0,0,paint);FlightCalendar.drawArrivalAnimation(canvas,animatedArrival,progress,animateWholeArrival,lat,lon,prefs.getInt("zoom",6),frame.getWidth(),frame.getHeight(),surfaceWidth,surfaceHeight,prefs);postFrame(frame);}catch(Throwable ignored){}
        }

        private void commitAnimatedArrival(){
            if(animationBase==null||animationBase.isRecycled()||animatedArrival==null)return;
            FlightCalendar.drawCompletedArrival(new Canvas(animationBase),animatedArrival,animateWholeArrival,lat,lon,prefs.getInt("zoom",6),animationBase.getWidth(),animationBase.getHeight(),surfaceWidth,surfaceHeight,prefs);postFrame(animationBase);
        }

        private void showCleanFrame(){Bitmap clean=lastFrameWithoutFlights;if(clean!=null&&!clean.isRecycled())postFrame(clean);}

        private void restoreHiddenPaths(){pathsHiddenForUnlock=false;if(lastFrame!=null&&!lastFrame.isRecycled())postFrame(lastFrame);}

        private void stopArrivalAnimation(boolean restore){if(worker!=null){worker.removeCallbacks(arrivalAnimation);worker.removeCallbacks(nextArrival);}animatedArrival=null;animateWholeArrival=false;arrivalSequence=Collections.emptyList();arrivalIndex=0;if(restore)restoreHiddenPaths();recycle(animationFrame);recycle(animationBase);animationFrame=null;animationBase=null;if(restore&&visible&&worker!=null){worker.removeCallbacks(refresh);worker.post(refresh);}}

        private void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled()&&bitmap!=baseMap&&bitmap!=lastFrame)bitmap.recycle();}

        private void drawFallback(){
            if(lastFrame!=null){postFrame(lastFrame);return;}
            SurfaceHolder h=getSurfaceHolder();Canvas c=null;
            try{if(h==null||h.getSurface()==null||!h.getSurface().isValid())return;c=h.lockCanvas();if(c!=null)c.drawColor(Color.rgb(7,16,22));}
            catch(Throwable ignored){}finally{if(c!=null)try{h.unlockCanvasAndPost(c);}catch(Throwable ignored){}}
        }
        private Bitmap getBitmap(String url,String palette){
            String cacheKey="gradient-v6|"+url+"|"+palette+"|"+Arrays.hashCode(PresetStore.colours(getApplicationContext(),PresetStore.RADAR,palette));Bitmap hit=cache.get(cacheKey);if(hit!=null)return hit;
            try{
                File disk=new File(radarCacheDir,cacheName(cacheKey));
                if(disk.isFile()){
                    Bitmap saved=BitmapFactory.decodeFile(disk.getAbsolutePath());
                    if(saved!=null){cache.put(cacheKey,saved);return saved;}
                }
                HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();con.setConnectTimeout(8000);con.setReadTimeout(12000);
                con.setRequestProperty("User-Agent","RadarWallpaper/0.12 (personal live wallpaper)");
                Bitmap b;try(InputStream in=con.getInputStream()){b=BitmapFactory.decodeStream(in);}finally{con.disconnect();}
                if(b!=null)b=recolourRadar(b,palette);
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
            return TaiwanPreviewRenderer.recolour(getApplicationContext(),source,palette);
        }
        private String readText(String url)throws Exception{
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()))){StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);return s.toString();}finally{c.disconnect();}
        }
    }
}
