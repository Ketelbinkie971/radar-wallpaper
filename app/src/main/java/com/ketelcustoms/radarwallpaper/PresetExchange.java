package com.ketelcustoms.radarwallpaper;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PresetExchange {
    static final String FORMAT="radar-wallpaper-preset";
    static final String MIME_JSON="application/json";
    static final String MIME_MAP="application/vnd.radarwallpaper.map-preset+json";
    static final String MIME_RADAR="application/vnd.radarwallpaper.radar-preset+json";
    private static final int MAX_BYTES=32*1024;

    static final class Data {
        final String type,name;final int[] colours;
        Data(String type,String name,int[] colours){this.type=type;this.name=name;this.colours=colours;}
    }

    static Uri writeSharedFile(Context context,String type,String name,int[] colours) throws Exception {
        validate(type,name,colours);
        JSONObject json=new JSONObject();json.put("format",FORMAT);json.put("version",1);json.put("type",type);json.put("name",name.trim());
        JSONArray values=new JSONArray();for(int colour:colours)values.put(String.format(Locale.US,"#%06X",0xFFFFFF&colour));json.put("colours",values);
        File folder=new File(context.getCacheDir(),"shared-presets");if(!folder.exists()&&!folder.mkdirs())throw new IOException("Could not create sharing folder");
        String suffix=PresetStore.MAP.equals(type)?"map":"radar";String clean=name.trim().replaceAll("[^\\p{L}\\p{N}._-]+","-").replaceAll("^-+|-+$","");if(clean.isEmpty())clean="preset";
        File file=new File(folder,clean+"."+suffix+".json");try(OutputStream out=new FileOutputStream(file)){out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));}
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".presetfiles").appendPath(file.getName()).build();
    }

    static Data read(Context context,Uri uri) throws Exception {
        if(uri==null)throw new IOException("No preset file was supplied");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("The preset file could not be opened");byte[] buffer=new byte[4096];int read,total=0;while((read=in.read(buffer))!=-1){total+=read;if(total>MAX_BYTES)throw new IOException("The preset file is too large");bytes.write(buffer,0,read);}}
        JSONObject json=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));if(!FORMAT.equals(json.optString("format"))||json.optInt("version")!=1)throw new IOException("This is not a Radar Wallpaper preset");
        String type=json.optString("type"),name=json.optString("name").trim();JSONArray array=json.optJSONArray("colours");if(array==null)throw new IOException("The preset has no colours");
        int[] colours=new int[array.length()];for(int i=0;i<colours.length;i++){String value=array.optString(i);if(!value.matches("#[0-9a-fA-F]{6}"))throw new IOException("The preset contains an invalid colour");colours[i]=Color.parseColor(value);}
        validate(type,name,colours);return new Data(type,name,colours);
    }

    static String mime(String type){return PresetStore.MAP.equals(type)?MIME_MAP:MIME_RADAR;}
    private static void validate(String type,String name,int[] colours) throws IOException {
        int expected=PresetStore.MAP.equals(type)?5:PresetStore.RADAR.equals(type)?7:-1;if(expected<0)throw new IOException("Unknown preset type");
        if(name==null||name.trim().isEmpty()||name.trim().length()>80)throw new IOException("The preset name is invalid");if(colours==null||colours.length!=expected)throw new IOException("The preset has the wrong number of colours");
    }
    private PresetExchange(){}
}
