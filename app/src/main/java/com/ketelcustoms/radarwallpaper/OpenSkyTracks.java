package com.ketelcustoms.radarwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

final class OpenSkyTracks {
    private static final long DAY=24L*60L*60L*1000L;
    private static final long RETRY_DELAY=DAY;
    private static final long LIST_CACHE_AGE=DAY;
    private static final long MANUAL_COOLDOWN=60L*60L*1000L;
    private static final long AUTO_INTERVAL=6L*60L*60L*1000L;
    private static String accessToken;
    private static long tokenExpiry;
    private static volatile int flightCredits=-1,trackCredits=-1;
    private static volatile long retryAfterSeconds=-1;

    static boolean attachCached(Context context,List<FlightCalendar.Leg> legs){
        boolean changed=false;
        for(FlightCalendar.Leg leg:legs){
            List<double[]> points=readTrack(trackFile(context,leg));
            if(points!=null){leg.actualTrack=points;leg.borrowedTrack=false;changed=true;continue;}
            points=readTrack(borrowedFile(context,leg));
            if(points!=null){leg.actualTrack=points;leg.borrowedTrack=true;changed=true;}
        }
        return changed;
    }

    static boolean downloadMissing(Context context,SharedPreferences prefs,List<FlightCalendar.Leg> legs,long now){
        return downloadMissing(context,prefs,legs,now,false);
    }

    private static boolean downloadMissing(Context context,SharedPreferences prefs,List<FlightCalendar.Leg> legs,long now,boolean manual){
        if(!prefs.getBoolean("opensky_actual_tracks",false))return false;
        String client=prefs.getString("opensky_client_id","").trim(),secret=prefs.getString("opensky_client_secret","").trim();
        if(client.isEmpty()||secret.isEmpty())return false;
        long blockedUntil=prefs.getLong("opensky_blocked_until",0);
        if(now<blockedUntil){setStatus(prefs,"OpenSky limit reached. Retry available in "+duration(blockedUntil-now)+".",now);return false;}
        if(!manual&&now-prefs.getLong("opensky_last_auto_check",0)<AUTO_INTERVAL)return false;

        FlightCalendar.Leg candidate=newestCandidate(context,legs,now);
        if(candidate==null){setStatus(prefs,"No uncached completed flights need checking.",now);return false;}
        if(!manual)prefs.edit().putLong("opensky_last_auto_check",now).apply();
        File miss=missFile(context,candidate);
        try{
            String token=token(client,secret);
            List<double[]> points=trackAt(context,token,candidate,candidate.start,now);
            if(points!=null){
                writeTrack(trackFile(context,candidate),points);
                File borrowed=borrowedFile(context,candidate);if(borrowed.isFile())borrowed.delete();
                if(miss.isFile())miss.delete();candidate.actualTrack=points;candidate.borrowedTrack=false;
                saveQuota(prefs);setStatus(prefs,"Downloaded the recorded track for "+label(candidate)+".",now);return true;
            }

            FlightCalendar.Leg newest=newestUncached(context,legs,now);boolean allowFallback=candidate==newest&&now-candidate.end<3*DAY;
            for(int days=1;allowFallback&&days<=2;days++){
                points=trackAt(context,token,candidate,candidate.start-days*DAY,now);
                if(points!=null){
                    writeTrack(borrowedFile(context,candidate),points);candidate.actualTrack=points;candidate.borrowedTrack=true;
                    markMiss(miss);saveQuota(prefs);setStatus(prefs,"Using the recorded "+label(candidate)+" track from "+days+" day"+(days==1?"":"s")+" earlier.",now);return true;
                }
            }
            markMiss(miss);saveQuota(prefs);setStatus(prefs,"No OpenSky match yet for "+label(candidate)+". It will retry tomorrow.",now);return false;
        }catch(Exception error){
            markMiss(miss);
            if(error instanceof HttpStatus&&((HttpStatus)error).status==429){long wait=retryAfterSeconds>0?retryAfterSeconds*1000L:DAY;prefs.edit().putLong("opensky_blocked_until",now+wait).apply();}
            saveQuota(prefs);setStatus(prefs,statusFor(error),now);return false;
        }
    }

    static boolean retryNow(Context context,SharedPreferences prefs,List<FlightCalendar.Leg> legs,long now){
        long blockedUntil=prefs.getLong("opensky_blocked_until",0);
        if(now<blockedUntil){setStatus(prefs,"OpenSky limit reached. Retry available in "+duration(blockedUntil-now)+".",now);return false;}
        long last=prefs.getLong("opensky_last_manual_check",0);
        if(now-last<MANUAL_COOLDOWN){setStatus(prefs,"Manual checks are limited to once per hour to protect your credits.",now);return false;}
        prefs.edit().putLong("opensky_last_manual_check",now).apply();
        FlightCalendar.Leg newest=newestUncached(context,legs,now);
        if(newest!=null){File miss=missFile(context,newest);if(miss.isFile())miss.delete();expireFlightLists(context,newest);}
        return downloadMissing(context,prefs,legs,now,true);
    }

    private static FlightCalendar.Leg newestCandidate(Context context,List<FlightCalendar.Leg> legs,long now){
        ArrayList<FlightCalendar.Leg> ordered=new ArrayList<>(legs);ordered.sort((a,b)->Long.compare(b.end,a.end));
        for(FlightCalendar.Leg leg:ordered){if(leg.end>now||trackFile(context,leg).isFile()||leg.fromIcao.isEmpty()||leg.toIcao.isEmpty())continue;File miss=missFile(context,leg);if(miss.isFile()&&now-miss.lastModified()<RETRY_DELAY)continue;return leg;}return null;
    }

    private static FlightCalendar.Leg newestUncached(Context context,List<FlightCalendar.Leg> legs,long now){
        FlightCalendar.Leg newest=null;for(FlightCalendar.Leg leg:legs)if(leg.end<=now&&!trackFile(context,leg).isFile()&&!leg.fromIcao.isEmpty()&&!leg.toIcao.isEmpty()&&(newest==null||leg.end>newest.end))newest=leg;return newest;
    }

    static int exactCount(Context context,List<FlightCalendar.Leg> legs){int count=0;for(FlightCalendar.Leg leg:legs)if(trackFile(context,leg).isFile())count++;return count;}
    static int borrowedCount(Context context,List<FlightCalendar.Leg> legs){int count=0;for(FlightCalendar.Leg leg:legs)if(!trackFile(context,leg).isFile()&&borrowedFile(context,leg).isFile())count++;return count;}

    private static List<double[]> trackAt(Context context,String token,FlightCalendar.Leg leg,long referenceStart,long now)throws Exception{
        JSONObject flight=findFlight(context,token,leg,referenceStart,now);if(flight==null)return null;
        return fetchTrack(token,flight.optString("icao24"),(flight.optLong("firstSeen")+flight.optLong("lastSeen"))/2);
    }

    private static JSONObject findFlight(Context context,String token,FlightCalendar.Leg leg,long referenceStart,long now)throws Exception{
        JSONArray flights=flightList(context,token,leg.fromIcao,referenceStart,now);JSONObject best=null;double bestScore=-1e9;String wantedDigits=leg.flightNumber.replaceAll("\\D","");
        for(int i=0;i<flights.length();i++){
            JSONObject flight=flights.getJSONObject(i);String departure=flight.optString("estDepartureAirport"),arrival=flight.optString("estArrivalAirport"),callsign=flight.optString("callsign","").trim();long first=flight.optLong("firstSeen")*1000L;double hours=Math.abs(first-referenceStart)/3600000.0;double score=-hours;
            if(leg.fromIcao.equals(departure))score+=5;if(leg.toIcao.equals(arrival))score+=8;String foundDigits=callsign.replaceAll("\\D","");if(!wantedDigits.isEmpty()&&wantedDigits.equals(foundDigits))score+=8;if(!leg.flightNumber.isEmpty()&&leg.flightNumber.equals(callsign.replaceAll("\\s+","")))score+=4;if(score>bestScore){bestScore=score;best=flight;}
        }
        return bestScore>=9?best:null;
    }

    private static JSONArray flightList(Context context,String token,String airport,long referenceStart,long now)throws Exception{
        long seconds=referenceStart/1000L,dayStart=Math.floorDiv(seconds,86400L)*86400L;File file=flightListFile(context,airport,dayStart);
        if(file.isFile()&&now-file.lastModified()<LIST_CACHE_AGE){JSONArray cached=readArray(file);if(cached!=null)return cached;}
        String url="https://opensky-network.org/api/flights/departure?airport="+encode(airport)+"&begin="+dayStart+"&end="+(dayStart+86399L);JSONArray result;
        try{result=new JSONArray(get(url,token));}catch(HttpStatus error){if(error.status!=404)throw error;result=new JSONArray();}
        writeArray(file,result);return result;
    }

    private static List<double[]> fetchTrack(String token,String icao24,long time)throws Exception{
        if(icao24==null||icao24.isEmpty()||time<=0)return null;
        try{JSONObject track=new JSONObject(get("https://opensky-network.org/api/tracks/all?icao24="+encode(icao24.toLowerCase(Locale.US))+"&time="+time,token));JSONArray path=track.optJSONArray("path");if(path==null)return null;ArrayList<double[]> points=new ArrayList<>();for(int i=0;i<path.length();i++){JSONArray point=path.optJSONArray(i);if(point==null||point.length()<3||point.isNull(1)||point.isNull(2))continue;double lat=point.optDouble(1,Double.NaN),lon=point.optDouble(2,Double.NaN);if(Double.isFinite(lat)&&Double.isFinite(lon))points.add(new double[]{lat,lon});}return points.size()>1?points:null;}catch(HttpStatus error){if(error.status==404)return null;throw error;}
    }

    private static synchronized String token(String client,String secret)throws Exception{
        long now=System.currentTimeMillis();if(accessToken!=null&&now+60_000L<tokenExpiry)return accessToken;String body="grant_type=client_credentials&client_id="+encode(client)+"&client_secret="+encode(secret);HttpsURLConnection con=(HttpsURLConnection)new URL("https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token").openConnection();con.setConnectTimeout(8000);con.setReadTimeout(12000);con.setRequestMethod("POST");con.setDoOutput(true);con.setRequestProperty("Content-Type","application/x-www-form-urlencoded");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);con.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=con.getOutputStream()){out.write(bytes);}String response=read(con);JSONObject json=new JSONObject(response);accessToken=json.getString("access_token");tokenExpiry=now+json.optLong("expires_in",1800)*1000L;return accessToken;
    }

    private static String get(String url,String token)throws Exception{HttpsURLConnection con=(HttpsURLConnection)new URL(url).openConnection();con.setConnectTimeout(8000);con.setReadTimeout(12000);con.setRequestProperty("Authorization","Bearer "+token);con.setRequestProperty("User-Agent","RadarWallpaper/0.32 private");return read(con);}

    private static String read(HttpsURLConnection con)throws Exception{
        try{int status=con.getResponseCode();captureQuota(con);InputStream input=status>=200&&status<300?con.getInputStream():con.getErrorStream();StringBuilder text=new StringBuilder();if(input!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)text.append(line);}if(status<200||status>=300)throw new HttpStatus(status);return text.toString();}finally{con.disconnect();}
    }

    private static void captureQuota(HttpsURLConnection con){try{String remaining=con.getHeaderField("X-Rate-Limit-Remaining"),path=con.getURL().getPath();if(remaining!=null){int value=Integer.parseInt(remaining);if(path.contains("/flights/"))flightCredits=value;else if(path.contains("/tracks/"))trackCredits=value;}String retry=con.getHeaderField("X-Rate-Limit-Retry-After-Seconds");if(retry!=null)retryAfterSeconds=Long.parseLong(retry);}catch(Exception ignored){}}
    private static void saveQuota(SharedPreferences prefs){SharedPreferences.Editor edit=prefs.edit();if(flightCredits>=0)edit.putInt("opensky_flight_credits",flightCredits);if(trackCredits>=0)edit.putInt("opensky_track_credits",trackCredits);edit.apply();}
    private static void setStatus(SharedPreferences prefs,String status,long now){prefs.edit().putString("opensky_last_status",status).putLong("opensky_last_check",now).apply();}
    private static String statusFor(Exception error){if(error instanceof HttpStatus){int status=((HttpStatus)error).status;if(status==401||status==403)return "OpenSky rejected the saved credentials (HTTP "+status+").";if(status==429)return "OpenSky API limit reached (HTTP 429).";return "OpenSky request failed with HTTP "+status+".";}if(error instanceof SocketTimeoutException)return "OpenSky timed out. Check the connection and retry.";return "OpenSky check failed: "+error.getClass().getSimpleName()+".";}
    private static String duration(long millis){long minutes=Math.max(1,(millis+59_999L)/60_000L);return minutes<120?minutes+" minutes":((minutes+59)/60)+" hours";}
    private static String label(FlightCalendar.Leg leg){return leg.flightNumber.isEmpty()?leg.from+"–"+leg.to:leg.flightNumber+" "+leg.from+"–"+leg.to;}

    private static File folder(Context context){File folder=new File(context.getFilesDir(),"flight-tracks");if(!folder.exists())folder.mkdirs();return folder;}
    private static File listFolder(Context context){File folder=new File(context.getFilesDir(),"opensky-flight-lists");if(!folder.exists())folder.mkdirs();return folder;}
    private static String key(FlightCalendar.Leg leg){return leg.start+"_"+leg.from+"_"+leg.to;}
    private static File trackFile(Context context,FlightCalendar.Leg leg){return new File(folder(context),key(leg)+".json");}
    private static File borrowedFile(Context context,FlightCalendar.Leg leg){return new File(folder(context),key(leg)+".borrowed.json");}
    private static File missFile(Context context,FlightCalendar.Leg leg){return new File(folder(context),key(leg)+".v32.miss");}
    private static File flightListFile(Context context,String airport,long dayStart){return new File(listFolder(context),airport+"_"+dayStart+".json");}
    private static void expireFlightLists(Context context,FlightCalendar.Leg leg){for(int days=0;days<=2;days++){long seconds=(leg.start-days*DAY)/1000L,dayStart=Math.floorDiv(seconds,86400L)*86400L;File file=flightListFile(context,leg.fromIcao,dayStart);if(file.isFile()&&System.currentTimeMillis()-file.lastModified()>MANUAL_COOLDOWN)file.delete();}}
    private static void markMiss(File file){try(FileOutputStream ignored=new FileOutputStream(file)){}catch(Exception ignored){}}
    private static void writeTrack(File file,List<double[]> points)throws Exception{JSONArray json=new JSONArray();for(double[] point:points){JSONArray value=new JSONArray();value.put(point[0]);value.put(point[1]);json.put(value);}writeArray(file,json);}
    private static void writeArray(File file,JSONArray json)throws Exception{try(OutputStream out=new FileOutputStream(file)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));}}
    private static JSONArray readArray(File file){try(BufferedReader reader=new BufferedReader(new FileReader(file))){StringBuilder text=new StringBuilder();String line;while((line=reader.readLine())!=null)text.append(line);return new JSONArray(text.toString());}catch(Exception ignored){return null;}}
    private static List<double[]> readTrack(File file){JSONArray json=readArray(file);if(json==null)return null;try{ArrayList<double[]> result=new ArrayList<>();for(int i=0;i<json.length();i++){JSONArray point=json.getJSONArray(i);result.add(new double[]{point.getDouble(0),point.getDouble(1)});}return result.size()>1?result:null;}catch(Exception ignored){return null;}}
    private static String encode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private static final class HttpStatus extends IOException{final int status;HttpStatus(int status){super("OpenSky HTTP "+status);this.status=status;}}
    private OpenSkyTracks(){}
}
