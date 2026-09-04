package com.ketelcustoms.radarwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import java.util.Arrays;

final class PresetStore {
    static final String RADAR="radar",MAP="map";
    private static final String[] RADAR_KEYS={"night","lagoon","sunset","orchid","polar"};
    private static final String[] RADAR_NAMES={"Butts Socks","Rens' Caipirinha","Krüters Klarinet","Ons-Low ICQ","Cemsto Clean"};
    private static final int[][] RADAR_COLOURS={
            {rgb(82,111,109),rgb(55,135,126),rgb(37,101,94),rgb(176,142,75),rgb(181,94,65),rgb(150,65,74),rgb(105,58,83)},
            {rgb(91,145,117),rgb(79,174,131),rgb(126,193,82),rgb(214,199,75),rgb(226,134,74),rgb(190,76,113),rgb(239,225,183)},
            {rgb(112,128,103),rgb(92,139,116),rgb(174,143,73),rgb(92,139,151),rgb(177,108,69),rgb(117,61,58),rgb(213,177,105)},
            {rgb(115,101,154),rgb(79,135,169),rgb(151,65,177),rgb(211,125,65),rgb(205,64,145),rgb(111,65,164),rgb(241,162,147)},
            {rgb(112,130,143),rgb(137,162,178),rgb(166,190,207),rgb(177,184,218),rgb(194,184,223),rgb(220,207,231),rgb(246,240,239)}
    };
    private static final String[] MAP_KEYS={"slate","navy","forest","plum","copper"};
    private static final String[] MAP_NAMES={"Schagchel Straat","Spaarne Control","Lange Veer","Du Theatre","Eenden Hok"};
    private static final int[][] MAP_COLOURS={
            {rgb(7,18,25),rgb(12,28,35),rgb(34,49,56),rgb(43,60,66),rgb(239,58,66)},
            {rgb(3,14,24),rgb(7,25,38),rgb(24,42,51),rgb(34,57,63),rgb(71,208,204)},
            {rgb(12,16,14),rgb(23,29,18),rgb(49,48,24),rgb(72,69,27),rgb(219,196,74)},
            {rgb(16,12,23),rgb(28,20,35),rgb(48,38,53),rgb(61,47,64),rgb(137,116,143)},
            {rgb(3,19,18),rgb(7,31,27),rgb(52,39,27),rgb(76,52,31),rgb(209,134,55)}
    };

    private static int rgb(int r,int g,int b){return Color.rgb(r,g,b);}
    static String[] keys(String type){return (MAP.equals(type)?MAP_KEYS:RADAR_KEYS).clone();}
    static String defaultName(String type,String key){int i=index(type,key);return MAP.equals(type)?MAP_NAMES[i]:RADAR_NAMES[i];}
    static int[] defaultColours(String type,String key){int i=index(type,key);return (MAP.equals(type)?MAP_COLOURS[i]:RADAR_COLOURS[i]).clone();}
    static String name(Context context,String type,String key){return prefs(context).getString("preset_name_"+type+"_"+key,defaultName(type,key));}
    static int[] colours(Context context,String type,String key){
        int[] fallback=defaultColours(type,key);String saved=prefs(context).getString("preset_colours_"+type+"_"+key,null);if(saved==null)return fallback;
        try{String[] pieces=saved.split(",");if(pieces.length!=fallback.length)return fallback;int[] result=new int[pieces.length];for(int i=0;i<pieces.length;i++)result[i]=Integer.parseInt(pieces[i]);return result;}catch(Exception ignored){return fallback;}
    }
    static void save(Context context,String type,String key,String name,int[] colours){
        StringBuilder encoded=new StringBuilder();for(int i=0;i<colours.length;i++){if(i>0)encoded.append(',');encoded.append(colours[i]);}
        prefs(context).edit().putString("preset_name_"+type+"_"+key,name.trim()).putString("preset_colours_"+type+"_"+key,encoded.toString()).apply();
    }
    static String cardName(String name){
        String clean=name.trim().replace('\n',' ');String[] words=clean.split("\\s+");if(words.length<2)return clean;int best=1,bestDistance=Integer.MAX_VALUE,length=0;
        for(int i=1;i<words.length;i++){length+=words[i-1].length()+(i>1?1:0);int distance=Math.abs(clean.length()/2-length);if(distance<bestDistance){bestDistance=distance;best=i;}}
        StringBuilder first=new StringBuilder(),second=new StringBuilder();for(int i=0;i<words.length;i++){StringBuilder target=i<best?first:second;if(target.length()>0)target.append(' ');target.append(words[i]);}return first+"\n"+second;
    }
    static int[] mapEditorColours(int[] internal){return new int[]{internal[3],internal[1],internal[4]};}
    static void setMapEditorColour(int[] internal,int position,int colour){
        if(position==0){internal[3]=colour;internal[2]=shade(colour,.76f);}else if(position==1){internal[1]=colour;internal[0]=shade(colour,.58f);}else internal[4]=colour;
    }
    private static int shade(int colour,float factor){float[] hsv=new float[3];Color.colorToHSV(colour,hsv);hsv[2]=Math.max(.02f,Math.min(1f,hsv[2]*factor));return Color.HSVToColor(hsv);}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("radar",Context.MODE_PRIVATE);}
    private static int index(String type,String key){String[] keys=MAP.equals(type)?MAP_KEYS:RADAR_KEYS;int index=Arrays.asList(keys).indexOf(key);return index<0?0:index;}
}
