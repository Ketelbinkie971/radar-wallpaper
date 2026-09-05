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
    private static volatile Map<String,double[]> airports;

    static final class Leg {
        final String from,to,title;final double fromLat,fromLon,toLat,toLon;final long start,end;
        Leg(String from,String to,String title,double[] a,double[] b,long start,long end){this.from=from;this.to=to;this.title=title;fromLat=a[0];fromLon=a[1];toLat=b[0];toLon=b[1];this.start=start;this.end=end;}
    }

    static List<Leg> load(Context context,SharedPreferences prefs,long now){
        ArrayList<Leg> result=new ArrayList<>();if(!prefs.getBoolean("flight_trails",false)||context.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)return result;
        long calendarId=prefs.getLong("flight_calendar_id",-1);if(calendarId<0)return result;long begin=now-7*DAY,end=now+7*DAY;
        Uri.Builder uri=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(uri,begin);ContentUris.appendId(uri,end);
        String[] projection={CalendarContract.Instances.TITLE,CalendarContract.Instances.DESCRIPTION,CalendarContract.Instances.EVENT_LOCATION,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END};
        try(Cursor cursor=context.getContentResolver().query(uri.build(),projection,CalendarContract.Instances.CALENDAR_ID+"=?",new String[]{Long.toString(calendarId)},CalendarContract.Instances.BEGIN+" ASC")){
            if(cursor==null)return result;Map<String,double[]> index=airportIndex(context);HashSet<String> seen=new HashSet<>();
            while(cursor.moveToNext()){
                String title=value(cursor,0),description=value(cursor,1),location=value(cursor,2);long start=cursor.getLong(3),finish=cursor.getLong(4);if(finish<=begin||start>=end)continue;
                List<String> codes=findAirportCodes(title,description,location,index);for(int i=0;i+1<codes.size();i++){
                    String from=codes.get(i),to=codes.get(i+1);if(from.equals(to))continue;String identity=start+"|"+from+"|"+to;if(seen.add(identity))result.add(new Leg(from,to,title,index.get(from),index.get(to),start,finish));
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
            Path path=projectGreatCircle(leg,world,cx,cy,bitmapW,bitmapH,surfaceW,surfaceH);if(path!=null)canvas.drawPath(path,line);
        }
        line.setPathEffect(null);
    }

    private static Path projectGreatCircle(Leg leg,double world,double cx,double cy,int w,int h,int surfaceW,int surfaceH){
        final int points=64;double lat1=Math.toRadians(leg.fromLat),lon1=Math.toRadians(leg.fromLon),lat2=Math.toRadians(leg.toLat),lon2=Math.toRadians(leg.toLon);
        double[] a={Math.cos(lat1)*Math.cos(lon1),Math.cos(lat1)*Math.sin(lon1),Math.sin(lat1)},b={Math.cos(lat2)*Math.cos(lon2),Math.cos(lat2)*Math.sin(lon2),Math.sin(lat2)};double omega=Math.acos(Math.max(-1,Math.min(1,a[0]*b[0]+a[1]*b[1]+a[2]*b[2]))),sin=Math.sin(omega);
        double[] xs=new double[points+1],ys=new double[points+1];for(int i=0;i<=points;i++){double t=i/(double)points,x,y,z;if(sin<1e-7){x=a[0]+(b[0]-a[0])*t;y=a[1]+(b[1]-a[1])*t;z=a[2]+(b[2]-a[2])*t;}else{double aa=Math.sin((1-t)*omega)/sin,bb=Math.sin(t*omega)/sin;x=aa*a[0]+bb*b[0];y=aa*a[1]+bb*b[1];z=aa*a[2]+bb*b[2];}double lat=Math.toDegrees(Math.atan2(z,Math.sqrt(x*x+y*y))),lon=Math.toDegrees(Math.atan2(y,x));xs[i]=worldX(lon,world);ys[i]=worldY(lat,world);if(i>0){while(xs[i]-xs[i-1]>world/2)xs[i]-=world;while(xs[i]-xs[i-1]<-world/2)xs[i]+=world;}}
        double mean=0;for(double x:xs)mean+=x;double shift=Math.rint((cx-mean/xs.length)/world)*world;Path path=new Path();for(int i=0;i<xs.length;i++){float sx=(float)(((xs[i]+shift-cx)*w/surfaceW)+w/2.0),sy=(float)(((ys[i]-cy)*h/surfaceH)+h/2.0);if(i==0)path.moveTo(sx,sy);else path.lineTo(sx,sy);}return path;
    }

    private static List<String> findAirportCodes(String title,String description,String location,Map<String,double[]> index){
        String heading=clean(title),all=heading+" "+clean(location)+" "+clean(description);ArrayList<String> explicit=new ArrayList<>();Matcher route=ROUTE.matcher(all);if(route.find()){addCode(explicit,route.group(1),index);addCode(explicit,route.group(2),index);}if(explicit.size()>=2)return explicit;
        if(!FLIGHT.matcher(all).find())return Collections.emptyList();ArrayList<String> codes=scanCodes(heading,index);if(codes.size()<2)codes=scanCodes(all,index);return collapse(codes);
    }
    private static ArrayList<String> scanCodes(String text,Map<String,double[]> index){ArrayList<String> codes=new ArrayList<>();Matcher matcher=CODE.matcher(text);while(matcher.find())addCode(codes,matcher.group(),index);return codes;}
    private static void addCode(List<String> values,String code,Map<String,double[]> index){if(index.containsKey(code)&&!(!values.isEmpty()&&values.get(values.size()-1).equals(code)))values.add(code);}
    private static List<String> collapse(List<String> values){if(values.size()<2)return Collections.emptyList();return values.size()>5?new ArrayList<>(values.subList(0,5)):values;}
    private static String clean(String value){return value==null?"":value.toUpperCase(Locale.US);}
    private static String value(Cursor cursor,int column){String value=cursor.getString(column);return value==null?"":value;}

    private static Map<String,double[]> airportIndex(Context context)throws IOException{Map<String,double[]> ready=airports;if(ready!=null)return ready;synchronized(FlightCalendar.class){if(airports!=null)return airports;HashMap<String,double[]> loaded=new HashMap<>();try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getResources().openRawResource(R.raw.airport_coordinates)))){String line;while((line=reader.readLine())!=null){String[] p=line.split(",");if(p.length==3)try{loaded.put(p[0],new double[]{Double.parseDouble(p[1]),Double.parseDouble(p[2])});}catch(NumberFormatException ignored){}}}airports=loaded;return loaded;}}
    private static double worldX(double lon,double world){return(lon+180.0)/360.0*world;}
    private static double worldY(double lat,double world){double s=Math.sin(Math.toRadians(Math.max(-85.05,Math.min(85.05,lat))));return(.5-Math.log((1+s)/(1-s))/(4*Math.PI))*world;}
    private FlightCalendar(){}
}
