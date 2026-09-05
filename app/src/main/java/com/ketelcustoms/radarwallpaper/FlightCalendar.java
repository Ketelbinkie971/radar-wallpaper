package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.provider.CalendarContract;
import java.io.*;
import java.util.*;
import java.util.regex.*;

final class FlightCalendar {
    static final long DAY=24L*60L*60L*1000L;
    private static final Pattern FLIGHT=Pattern.compile("\\b[A-Z]{2,3}\\s?\\d{2,4}[A-Z]?\\b");
    private static final Pattern ROUTE=Pattern.compile("\\b([A-Z]{3,4})\\s*(?:-|–|—|>|→|/|\\bTO\\b)\\s*([A-Z]{3,4})\\b");
    private static final Pattern CODE=Pattern.compile("\\b[A-Z]{3,4}\\b");
    private static volatile Map<String,Airport> airports;

    static final class Airport {
        final double lat,lon;final String icao;
        Airport(double lat,double lon,String icao){this.lat=lat;this.lon=lon;this.icao=icao;}
    }

    static final class Leg {
        final String from,to,fromIcao,toIcao,title,flightNumber;final double fromLat,fromLon,toLat,toLon;final long start,end;List<double[]> actualTrack;boolean borrowedTrack;
        Leg(String from,String to,String title,String flightNumber,Airport a,Airport b,long start,long end){this.from=from;this.to=to;fromIcao=a.icao;toIcao=b.icao;this.title=title;this.flightNumber=flightNumber;fromLat=a.lat;fromLon=a.lon;toLat=b.lat;toLon=b.lon;this.start=start;this.end=end;}
    }

    static List<Leg> load(Context context,SharedPreferences prefs,long now){
        ArrayList<Leg> result=new ArrayList<>();if(!prefs.getBoolean("flight_trails",false)||context.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)return result;
        long calendarId=prefs.getLong("flight_calendar_id",-1);if(calendarId<0)return result;long begin=now-7*DAY,end=now+7*DAY;
        Uri.Builder uri=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(uri,begin);ContentUris.appendId(uri,end);
        String[] projection={CalendarContract.Instances.TITLE,CalendarContract.Instances.DESCRIPTION,CalendarContract.Instances.EVENT_LOCATION,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END};
        try(Cursor cursor=context.getContentResolver().query(uri.build(),projection,CalendarContract.Instances.CALENDAR_ID+"=?",new String[]{Long.toString(calendarId)},CalendarContract.Instances.BEGIN+" ASC")){
            if(cursor==null)return result;Map<String,Airport> index=airportIndex(context);HashSet<String> seen=new HashSet<>();
            while(cursor.moveToNext()){
                String title=value(cursor,0),description=value(cursor,1),location=value(cursor,2);long start=cursor.getLong(3),finish=cursor.getLong(4);if(finish<=begin||start>=end)continue;
                String combined=clean(title)+" "+clean(location)+" "+clean(description);List<String> codes=findAirportCodes(title,description,location,index);for(int i=0;i+1<codes.size();i++){
                    String from=codes.get(i),to=codes.get(i+1);if(from.equals(to))continue;String identity=start+"|"+from+"|"+to;if(seen.add(identity))result.add(new Leg(from,to,title,flightNumber(combined),index.get(from),index.get(to),start,finish));
                }
            }
        }catch(Exception ignored){}
        return result;
    }

    static int count(Context context,SharedPreferences prefs){return load(context,prefs,System.currentTimeMillis()).size();}

    static void draw(Canvas canvas,List<Leg> legs,double centerLat,double centerLon,int zoom,int bitmapW,int bitmapH,int surfaceW,int surfaceH,SharedPreferences prefs){
        if(legs==null||legs.isEmpty())return;double world=256.0*(1<<zoom),cx=worldX(centerLon,world),cy=worldY(centerLat,world);long now=System.currentTimeMillis();int base=prefs.getInt("flight_trail_color",Color.rgb(126,207,214));float width=Math.max(.8f,prefs.getInt("flight_trail_width",2)*bitmapW/900f);
        Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);line.setStyle(Paint.Style.STROKE);line.setStrokeCap(Paint.Cap.ROUND);line.setStrokeJoin(Paint.Join.ROUND);
        for(Leg leg:legs){boolean future=leg.start>now,current=leg.start<=now&&leg.end>=now;float distanceDays=Math.min(7f,Math.abs(leg.start-now)/(float)DAY);float fade=1f-distanceDays/7f;int alpha=current?225:future?Math.round(18+112*fade):Math.round(24+156*fade);
            float[] hsv=new float[3];Color.colorToHSV(base,hsv);if(!future&&!current)hsv[1]*=.35f+.65f*fade;line.setColor(Color.HSVToColor(alpha,hsv));line.setStrokeWidth(width*(current?1.7f:1f));line.setPathEffect(future?new DashPathEffect(new float[]{width*5,width*4},0):null);
            Path path=leg.actualTrack!=null&&leg.actualTrack.size()>1?projectPoints(leg.actualTrack,world,cx,cy,bitmapW,bitmapH,surfaceW,surfaceH):projectGreatCircle(leg,world,cx,cy,bitmapW,bitmapH,surfaceW,surfaceH);if(path!=null)canvas.drawPath(path,line);
        }
        line.setPathEffect(null);
    }

    static void drawArrivalAnimation(Canvas canvas,Leg leg,float progress,double centerLat,double centerLon,int zoom,int bitmapW,int bitmapH,int surfaceW,int surfaceH,SharedPreferences prefs){
        if(leg==null)return;float eased=1f-(1f-progress)*(1f-progress),routeProgress=.68f+.32f*eased,tailStart=Math.max(.68f,routeProgress-.055f);double world=256.0*(1<<zoom),cx=worldX(centerLon,world),cy=worldY(centerLat,world);int colour=prefs.getInt("flight_trail_color",Color.rgb(126,207,214)),accent=vivid(colour);float scale=Math.max(.75f,bitmapW/900f);Path tail=new Path();float headX=0,headY=0;
        for(int i=0;i<=14;i++){float fraction=tailStart+(routeProgress-tailStart)*i/14f;double[] point=routePoint(leg,fraction);float x=screenX(point[1],world,cx,bitmapW,surfaceW),y=(float)(((worldY(point[0],world)-cy)*bitmapH/surfaceH)+bitmapH/2.0);if(i==0)tail.moveTo(x,y);else tail.lineTo(x,y);headX=x;headY=y;}
        Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG);glow.setStyle(Paint.Style.STROKE);glow.setStrokeCap(Paint.Cap.ROUND);glow.setStrokeWidth(6f*scale);glow.setColor(withAlpha(colour,45));canvas.drawPath(tail,glow);glow.setStrokeWidth(2.2f*scale);glow.setColor(withAlpha(colour,205));canvas.drawPath(tail,glow);
        glow.setStyle(Paint.Style.FILL);glow.setColor(withAlpha(accent,55));canvas.drawCircle(headX,headY,14f*scale,glow);glow.setColor(withAlpha(accent,145));canvas.drawCircle(headX,headY,8f*scale,glow);glow.setColor(withAlpha(accent,255));canvas.drawCircle(headX,headY,4.6f*scale,glow);glow.setColor(Color.WHITE);glow.setAlpha(235);canvas.drawCircle(headX,headY,1.6f*scale,glow);
        if(progress>.82f){float pulse=(progress-.82f)/.18f;glow.setStyle(Paint.Style.STROKE);glow.setStrokeWidth(1.4f*scale);glow.setColor(withAlpha(colour,Math.round(150*(1-pulse))));double[] destination=routePoint(leg,1f);float dx=screenX(destination[1],world,cx,bitmapW,surfaceW),dy=(float)(((worldY(destination[0],world)-cy)*bitmapH/surfaceH)+bitmapH/2.0);canvas.drawCircle(dx,dy,(5f+15f*pulse)*scale,glow);}
    }

    private static double[] routePoint(Leg leg,float fraction){
        if(leg.actualTrack!=null&&leg.actualTrack.size()>1){int count=leg.actualTrack.size()+1;float position=fraction*(count-1);int lower=Math.min(count-2,(int)Math.floor(position));float mix=position-lower;double[] a=leg.actualTrack.get(Math.min(lower,leg.actualTrack.size()-1)),b=lower+1<leg.actualTrack.size()?leg.actualTrack.get(lower+1):new double[]{leg.toLat,leg.toLon};return new double[]{a[0]+(b[0]-a[0])*mix,interpolateLongitude(a[1],b[1],mix)};}
        double t=fraction,lat1=Math.toRadians(leg.fromLat),lon1=Math.toRadians(leg.fromLon),lat2=Math.toRadians(leg.toLat),lon2=Math.toRadians(leg.toLon);double[] a={Math.cos(lat1)*Math.cos(lon1),Math.cos(lat1)*Math.sin(lon1),Math.sin(lat1)},b={Math.cos(lat2)*Math.cos(lon2),Math.cos(lat2)*Math.sin(lon2),Math.sin(lat2)};double omega=Math.acos(Math.max(-1,Math.min(1,a[0]*b[0]+a[1]*b[1]+a[2]*b[2]))),sin=Math.sin(omega),x,y,z;if(sin<1e-7){x=a[0]+(b[0]-a[0])*t;y=a[1]+(b[1]-a[1])*t;z=a[2]+(b[2]-a[2])*t;}else{double aa=Math.sin((1-t)*omega)/sin,bb=Math.sin(t*omega)/sin;x=aa*a[0]+bb*b[0];y=aa*a[1]+bb*b[1];z=aa*a[2]+bb*b[2];}return new double[]{Math.toDegrees(Math.atan2(z,Math.sqrt(x*x+y*y))),Math.toDegrees(Math.atan2(y,x))};
    }

    private static double interpolateLongitude(double from,double to,float mix){double delta=to-from;while(delta>180)delta-=360;while(delta<-180)delta+=360;double value=from+delta*mix;while(value>180)value-=360;while(value<-180)value+=360;return value;}
    private static int vivid(int colour){float[] hsv=new float[3];Color.colorToHSV(colour,hsv);hsv[1]=Math.max(.62f,hsv[1]);hsv[2]=1f;return Color.HSVToColor(hsv);}
    private static float screenX(double lon,double world,double cx,int width,int surfaceW){double x=worldX(lon,world);while(x-cx>world/2)x-=world;while(x-cx<-world/2)x+=world;return(float)(((x-cx)*width/surfaceW)+width/2.0);}
    private static int withAlpha(int colour,int alpha){return Color.argb(Math.max(0,Math.min(255,alpha)),Color.red(colour),Color.green(colour),Color.blue(colour));}

    private static Path projectGreatCircle(Leg leg,double world,double cx,double cy,int w,int h,int surfaceW,int surfaceH){
        final int points=64;double lat1=Math.toRadians(leg.fromLat),lon1=Math.toRadians(leg.fromLon),lat2=Math.toRadians(leg.toLat),lon2=Math.toRadians(leg.toLon);
        double[] a={Math.cos(lat1)*Math.cos(lon1),Math.cos(lat1)*Math.sin(lon1),Math.sin(lat1)},b={Math.cos(lat2)*Math.cos(lon2),Math.cos(lat2)*Math.sin(lon2),Math.sin(lat2)};double omega=Math.acos(Math.max(-1,Math.min(1,a[0]*b[0]+a[1]*b[1]+a[2]*b[2]))),sin=Math.sin(omega);
        double[] xs=new double[points+1],ys=new double[points+1];for(int i=0;i<=points;i++){double t=i/(double)points,x,y,z;if(sin<1e-7){x=a[0]+(b[0]-a[0])*t;y=a[1]+(b[1]-a[1])*t;z=a[2]+(b[2]-a[2])*t;}else{double aa=Math.sin((1-t)*omega)/sin,bb=Math.sin(t*omega)/sin;x=aa*a[0]+bb*b[0];y=aa*a[1]+bb*b[1];z=aa*a[2]+bb*b[2];}double lat=Math.toDegrees(Math.atan2(z,Math.sqrt(x*x+y*y))),lon=Math.toDegrees(Math.atan2(y,x));xs[i]=worldX(lon,world);ys[i]=worldY(lat,world);if(i>0){while(xs[i]-xs[i-1]>world/2)xs[i]-=world;while(xs[i]-xs[i-1]<-world/2)xs[i]+=world;}}
        double mean=0;for(double x:xs)mean+=x;double shift=Math.rint((cx-mean/xs.length)/world)*world;Path path=new Path();for(int i=0;i<xs.length;i++){float sx=(float)(((xs[i]+shift-cx)*w/surfaceW)+w/2.0),sy=(float)(((ys[i]-cy)*h/surfaceH)+h/2.0);if(i==0)path.moveTo(sx,sy);else path.lineTo(sx,sy);}return path;
    }

    private static Path projectPoints(List<double[]> points,double world,double cx,double cy,int w,int h,int surfaceW,int surfaceH){
        int count=points.size();double[] xs=new double[count],ys=new double[count];for(int i=0;i<count;i++){double[] point=points.get(i);xs[i]=worldX(point[1],world);ys[i]=worldY(point[0],world);if(i>0){while(xs[i]-xs[i-1]>world/2)xs[i]-=world;while(xs[i]-xs[i-1]<-world/2)xs[i]+=world;}}
        double mean=0;for(double x:xs)mean+=x;double shift=Math.rint((cx-mean/count)/world)*world;Path path=new Path();for(int i=0;i<count;i++){float sx=(float)(((xs[i]+shift-cx)*w/surfaceW)+w/2.0),sy=(float)(((ys[i]-cy)*h/surfaceH)+h/2.0);if(i==0)path.moveTo(sx,sy);else path.lineTo(sx,sy);}return path;
    }

    private static List<String> findAirportCodes(String title,String description,String location,Map<String,Airport> index){
        String heading=clean(title),all=heading+" "+clean(location)+" "+clean(description);ArrayList<String> explicit=new ArrayList<>();Matcher route=ROUTE.matcher(all);if(route.find()){addCode(explicit,route.group(1),index);addCode(explicit,route.group(2),index);}if(explicit.size()>=2)return explicit;
        if(!FLIGHT.matcher(all).find())return Collections.emptyList();ArrayList<String> codes=scanCodes(heading,index);if(codes.size()<2)codes=scanCodes(all,index);return collapse(codes);
    }
    private static ArrayList<String> scanCodes(String text,Map<String,Airport> index){ArrayList<String> codes=new ArrayList<>();Matcher matcher=CODE.matcher(text);while(matcher.find())addCode(codes,matcher.group(),index);return codes;}
    private static void addCode(List<String> values,String code,Map<String,Airport> index){if(index.containsKey(code)&&!(!values.isEmpty()&&values.get(values.size()-1).equals(code)))values.add(code);}
    private static List<String> collapse(List<String> values){if(values.size()<2)return Collections.emptyList();return values.size()>5?new ArrayList<>(values.subList(0,5)):values;}
    private static String clean(String value){return value==null?"":value.toUpperCase(Locale.US);}
    private static String flightNumber(String value){Matcher matcher=FLIGHT.matcher(value);return matcher.find()?matcher.group().replaceAll("\\s+",""):"";}
    private static String value(Cursor cursor,int column){String value=cursor.getString(column);return value==null?"":value;}

    private static Map<String,Airport> airportIndex(Context context)throws IOException{Map<String,Airport> ready=airports;if(ready!=null)return ready;synchronized(FlightCalendar.class){if(airports!=null)return airports;HashMap<String,Airport> loaded=new HashMap<>();try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getResources().openRawResource(R.raw.airport_coordinates)))){String line;while((line=reader.readLine())!=null){String[] p=line.split(",",-1);if(p.length>=4)try{loaded.put(p[0],new Airport(Double.parseDouble(p[1]),Double.parseDouble(p[2]),p[3]));}catch(NumberFormatException ignored){}}}airports=loaded;return loaded;}}
    private static double worldX(double lon,double world){return(lon+180.0)/360.0*world;}
    private static double worldY(double lat,double world){double s=Math.sin(Math.toRadians(Math.max(-85.05,Math.min(85.05,lat))));return(.5-Math.log((1+s)/(1-s))/(4*Math.PI))*world;}
    private FlightCalendar(){}
}
