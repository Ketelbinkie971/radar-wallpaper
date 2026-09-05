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
    private static final long RETRY_DELAY=60L*60L*1000L;
    private static String accessToken;private static long tokenExpiry;

    static boolean attachCached(Context context,List<FlightCalendar.Leg> legs){boolean changed=false;for(FlightCalendar.Leg leg:legs){List<double[]> points=readTrack(trackFile(context,leg));if(points!=null){leg.actualTrack=points;leg.borrowedTrack=false;changed=true;continue;}points=readTrack(borrowedFile(context,leg));if(points!=null){leg.actualTrack=points;leg.borrowedTrack=true;changed=true;}}return changed;}

    static boolean downloadMissing(Context context,SharedPreferences prefs,List<FlightCalendar.Leg> legs,long now){
        if(!prefs.getBoolean("opensky_actual_tracks",false))return false;String client=prefs.getString("opensky_client_id","").trim(),secret=prefs.getString("opensky_client_secret","").trim();if(client.isEmpty()||secret.isEmpty())return false;
        String token=null,lastError=null;boolean changed=false;int attempted=0,downloaded=0,noMatch=0;for(FlightCalendar.Leg leg:legs){File exact=trackFile(context,leg);if(leg.start>=now||exact.isFile()||leg.fromIcao.isEmpty()||leg.toIcao.isEmpty())continue;File miss=missFile(context,leg);if(miss.isFile()&&now-miss.lastModified()<RETRY_DELAY)continue;if(attempted++>=3)break;
            try{if(token==null)token=token(client,secret);List<double[]> points=trackAt(token,leg,leg.start);if(points!=null){writeTrack(exact,points);File borrowed=borrowedFile(context,leg);if(borrowed.isFile())borrowed.delete();if(miss.isFile())miss.delete();leg.actualTrack=points;leg.borrowedTrack=false;changed=true;downloaded++;continue;}
                File borrowed=borrowedFile(context,leg);boolean found=borrowed.isFile();if(!found){for(int days=1;days<=2;days++){points=trackAt(token,leg,leg.start-days*FlightCalendar.DAY);if(points!=null){writeTrack(borrowed,points);leg.actualTrack=points;leg.borrowedTrack=true;changed=true;downloaded++;found=true;break;}}}if(!found)noMatch++;markMiss(miss);
            }catch(Exception error){lastError=statusFor(error);markMiss(miss);}
        }String status;if(downloaded>0)status="Downloaded "+downloaded+" recorded track"+(downloaded==1?"":"s")+".";else if(lastError!=null)status=lastError;else if(attempted==0)status="No uncached completed flights need checking.";else status="No OpenSky match yet for "+noMatch+" flight"+(noMatch==1?"":"s")+". Recent flights may appear after overnight processing.";prefs.edit().putString("opensky_last_status",status).putLong("opensky_last_check",now).apply();return changed;
    }

    static boolean retryNow(Context context,SharedPreferences prefs,List<FlightCalendar.Leg> legs,long now){for(FlightCalendar.Leg leg:legs){File miss=missFile(context,leg);if(miss.isFile())miss.delete();}return downloadMissing(context,prefs,legs,now);}

    static int exactCount(Context context,List<FlightCalendar.Leg> legs){int count=0;for(FlightCalendar.Leg leg:legs)if(trackFile(context,leg).isFile())count++;return count;}
    static int borrowedCount(Context context,List<FlightCalendar.Leg> legs){int count=0;for(FlightCalendar.Leg leg:legs)if(!trackFile(context,leg).isFile()&&borrowedFile(context,leg).isFile())count++;return count;}

    private static List<double[]> trackAt(String token,FlightCalendar.Leg leg,long referenceStart)throws Exception{JSONObject flight=findFlight(token,leg,referenceStart);if(flight==null)return null;return fetchTrack(token,flight.optString("icao24"),(flight.optLong("firstSeen")+flight.optLong("lastSeen"))/2);}

    private static JSONObject findFlight(String token,FlightCalendar.Leg leg,long referenceStart)throws Exception{
        try{long begin=(referenceStart-8L*60L*60L*1000L)/1000L,end=(referenceStart+8L*60L*60L*1000L)/1000L;String url="https://opensky-network.org/api/flights/departure?airport="+encode(leg.fromIcao)+"&begin="+begin+"&end="+end;JSONArray flights=new JSONArray(get(url,token));JSONObject best=null;double bestScore=-1e9;String wantedDigits=leg.flightNumber.replaceAll("\\D","");
            for(int i=0;i<flights.length();i++){JSONObject flight=flights.getJSONObject(i);String departure=flight.optString("estDepartureAirport"),arrival=flight.optString("estArrivalAirport"),callsign=flight.optString("callsign","").trim();long first=flight.optLong("firstSeen")*1000L;double hours=Math.abs(first-referenceStart)/3600000.0;double score=-hours;if(leg.fromIcao.equals(departure))score+=5;if(leg.toIcao.equals(arrival))score+=8;String foundDigits=callsign.replaceAll("\\D","");if(!wantedDigits.isEmpty()&&wantedDigits.equals(foundDigits))score+=8;if(!leg.flightNumber.isEmpty()&&leg.flightNumber.equals(callsign.replaceAll("\\s+","")))score+=4;if(score>bestScore){bestScore=score;best=flight;}}
            return bestScore>=9?best:null;}catch(HttpStatus error){if(error.status==404)return null;throw error;}
    }

    private static List<double[]> fetchTrack(String token,String icao24,long time)throws Exception{if(icao24==null||icao24.isEmpty()||time<=0)return null;try{JSONObject track=new JSONObject(get("https://opensky-network.org/api/tracks/all?icao24="+encode(icao24.toLowerCase(Locale.US))+"&time="+time,token));JSONArray path=track.optJSONArray("path");if(path==null)return null;ArrayList<double[]> points=new ArrayList<>();for(int i=0;i<path.length();i++){JSONArray point=path.optJSONArray(i);if(point==null||point.length()<3||point.isNull(1)||point.isNull(2))continue;double lat=point.optDouble(1,Double.NaN),lon=point.optDouble(2,Double.NaN);if(Double.isFinite(lat)&&Double.isFinite(lon))points.add(new double[]{lat,lon});}return points;}catch(HttpStatus error){if(error.status==404)return null;throw error;}}

    private static synchronized String token(String client,String secret)throws Exception{long now=System.currentTimeMillis();if(accessToken!=null&&now+60_000L<tokenExpiry)return accessToken;String body="grant_type=client_credentials&client_id="+encode(client)+"&client_secret="+encode(secret);HttpsURLConnection con=(HttpsURLConnection)new URL("https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token").openConnection();con.setConnectTimeout(8000);con.setReadTimeout(12000);con.setRequestMethod("POST");con.setDoOutput(true);con.setRequestProperty("Content-Type","application/x-www-form-urlencoded");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);con.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=con.getOutputStream()){out.write(bytes);}String response=read(con);JSONObject json=new JSONObject(response);accessToken=json.getString("access_token");tokenExpiry=now+json.optLong("expires_in",1800)*1000L;return accessToken;}

    private static String get(String url,String token)throws Exception{HttpsURLConnection con=(HttpsURLConnection)new URL(url).openConnection();con.setConnectTimeout(8000);con.setReadTimeout(12000);con.setRequestProperty("Authorization","Bearer "+token);con.setRequestProperty("User-Agent","RadarWallpaper/0.25 private");return read(con);}
    private static String read(HttpsURLConnection con)throws Exception{try{int status=con.getResponseCode();InputStream input=status>=200&&status<300?con.getInputStream():con.getErrorStream();StringBuilder text=new StringBuilder();if(input!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)text.append(line);}if(status<200||status>=300)throw new HttpStatus(status);return text.toString();}finally{con.disconnect();}}

    private static String statusFor(Exception error){if(error instanceof HttpStatus){int status=((HttpStatus)error).status;if(status==401||status==403)return "OpenSky rejected the saved credentials (HTTP "+status+").";if(status==429)return "OpenSky request limit reached (HTTP 429). Try again later.";return "OpenSky request failed with HTTP "+status+".";}if(error instanceof SocketTimeoutException)return "OpenSky timed out. Check the connection and retry.";return "OpenSky check failed: "+error.getClass().getSimpleName()+".";}
    private static final class HttpStatus extends IOException{final int status;HttpStatus(int status){super("OpenSky HTTP "+status);this.status=status;}}

    private static File folder(Context context){File folder=new File(context.getFilesDir(),"flight-tracks");if(!folder.exists())folder.mkdirs();return folder;}
    private static String key(FlightCalendar.Leg leg){return leg.start+"_"+leg.from+"_"+leg.to;}
    private static File trackFile(Context context,FlightCalendar.Leg leg){return new File(folder(context),key(leg)+".json");}
    private static File borrowedFile(Context context,FlightCalendar.Leg leg){return new File(folder(context),key(leg)+".borrowed.json");}
    private static File missFile(Context context,FlightCalendar.Leg leg){return new File(folder(context),key(leg)+".v25.miss");}
    private static void markMiss(File file){try(FileOutputStream ignored=new FileOutputStream(file)){}catch(Exception ignored){}}
    private static void writeTrack(File file,List<double[]> points)throws Exception{JSONArray json=new JSONArray();for(double[] point:points){JSONArray value=new JSONArray();value.put(point[0]);value.put(point[1]);json.put(value);}try(OutputStream out=new FileOutputStream(file)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));}}
    private static List<double[]> readTrack(File file){if(!file.isFile())return null;try(BufferedReader reader=new BufferedReader(new FileReader(file))){StringBuilder text=new StringBuilder();String line;while((line=reader.readLine())!=null)text.append(line);JSONArray json=new JSONArray(text.toString());ArrayList<double[]> result=new ArrayList<>();for(int i=0;i<json.length();i++){JSONArray point=json.getJSONArray(i);result.add(new double[]{point.getDouble(0),point.getDouble(1)});}return result.size()>1?result:null;}catch(Exception ignored){return null;}}
    private static String encode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private OpenSkyTracks(){}
}
