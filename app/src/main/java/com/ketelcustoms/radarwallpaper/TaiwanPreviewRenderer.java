package com.ketelcustoms.radarwallpaper;

import android.content.Context;
import android.graphics.*;
import org.json.JSONArray;
import java.io.*;
import java.util.*;

final class TaiwanPreviewRenderer {
    static final double LAT=25.0330,LON=121.5654;
    private static List<List<float[]>> geography;
    private static final Map<String,Bitmap> tileCache=Collections.synchronizedMap(new LinkedHashMap<String,Bitmap>(48,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,Bitmap> entry){return size()>42;}
    });

    static Bitmap render(Context context,int width,int height,int zoom,String mapTheme,String palette,int opacity){
        int w=Math.max(1,Math.min(width,900)),h=Math.max(1,Math.round(w*(height/(float)Math.max(1,width))));
        double renderScale=w/(double)Math.max(1,width);
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);int[] theme=mapColours(mapTheme);
        Paint ocean=new Paint();ocean.setShader(new LinearGradient(0,0,0,h,theme[0],theme[1],Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,ocean);
        double world=256.0*(1<<zoom),cx=worldX(LON,world),cy=worldY(LAT,world);
        try{
            Paint land=new Paint(Paint.ANTI_ALIAS_FLAG);land.setStyle(Paint.Style.FILL);land.setShader(new LinearGradient(0,0,0,h,theme[2],theme[3],Shader.TileMode.CLAMP));
            Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(Math.max(1f,w/1100f));border.setColor(theme[4]);border.setAlpha(220);
            for(List<float[]> polygon:getGeography(context)){
                Path path=new Path();path.setFillType(Path.FillType.EVEN_ODD);
                for(float[] ring:polygon){
                    int count=ring.length/2;if(count<3)continue;double[] xs=new double[count];xs[0]=worldX(ring[0],world);double sum=xs[0];
                    for(int p=1;p<count;p++){double x=worldX(ring[p*2],world),previous=xs[p-1];while(x-previous>world/2)x-=world;while(x-previous<-world/2)x+=world;xs[p]=x;sum+=x;}
                    double shift=Math.rint((cx-sum/count)/world)*world;boolean started=false;
                    for(int p=0;p<count;p++){int i=p*2;float sx=(float)((xs[p]+shift-cx)*renderScale+w/2.0),sy=(float)((worldY(ring[i+1],world)-cy)*renderScale+h/2.0);if(!started){path.moveTo(sx,sy);started=true;}else path.lineTo(sx,sy);}path.close();
                }
                canvas.drawPath(path,land);canvas.drawPath(path,border);
            }
        }catch(Throwable ignored){}

        Paint radarPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);radarPaint.setAlpha((int)(255*Math.max(0,Math.min(100,opacity))/100f));int tile=256,n=1<<zoom;
        int minX=(int)Math.floor((cx-width/2.0)/tile),maxX=(int)Math.floor((cx+width/2.0)/tile),minY=(int)Math.floor((cy-height/2.0)/tile),maxY=(int)Math.floor((cy+height/2.0)/tile);
        for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++){
            if(y<0||y>=n)continue;int nx=((x%n)+n)%n;Bitmap radar=loadTile(context,zoom,nx,y,palette);if(radar==null)continue;
            float left=(float)((x*tile-(cx-width/2.0))*renderScale),top=(float)((y*tile-(cy-height/2.0))*renderScale),drawTile=(float)(tile*renderScale);canvas.drawBitmap(radar,null,new RectF(left,top,left+drawTile,top+drawTile),radarPaint);
        }
        Paint marker=new Paint(Paint.ANTI_ALIAS_FLAG);marker.setColor(Color.rgb(240,247,248));marker.setAlpha(235);canvas.drawCircle(w/2f,h/2f,5,marker);
        marker.setStyle(Paint.Style.STROKE);marker.setStrokeWidth(2);marker.setColor(Color.rgb(25,50,61));canvas.drawCircle(w/2f,h/2f,9,marker);
        return result;
    }

    private static Bitmap loadTile(Context context,int zoom,int x,int y,String palette){
        String key="taiwan-v1|"+zoom+"|"+x+"|"+y+"|"+palette;Bitmap hit=tileCache.get(key);if(hit!=null&&!hit.isRecycled())return hit;
        try(InputStream input=context.getAssets().open("taiwan_radar/z"+zoom+"/"+x+"_"+y+".png")){
            Bitmap source=BitmapFactory.decodeStream(input);if(source==null)return null;Bitmap coloured=recolour(source,palette);if(coloured!=source)source.recycle();tileCache.put(key,coloured);return coloured;
        }catch(Throwable ignored){return null;}
    }

    private static synchronized List<List<float[]>> getGeography(Context context)throws Exception{
        if(geography!=null)return geography;List<List<float[]>> result=new ArrayList<>();StringBuilder text=new StringBuilder();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getResources().openRawResource(R.raw.natural_earth_countries)))){String line;while((line=reader.readLine())!=null)text.append(line);}
        JSONArray polygons=new JSONArray(text.toString());
        for(int p=0;p<polygons.length();p++){JSONArray ringsJson=polygons.getJSONArray(p);List<float[]> rings=new ArrayList<>();for(int r=0;r<ringsJson.length();r++){JSONArray points=ringsJson.getJSONArray(r);float[] ring=new float[points.length()*2];for(int i=0;i<points.length();i++){JSONArray point=points.getJSONArray(i);ring[i*2]=(float)point.getDouble(0);ring[i*2+1]=(float)point.getDouble(1);}rings.add(ring);}result.add(rings);}
        geography=result;return geography;
    }

    static int[] mapColours(String theme){
        if("navy".equals(theme))return new int[]{Color.rgb(3,14,24),Color.rgb(7,25,38),Color.rgb(24,42,51),Color.rgb(34,57,63),Color.rgb(71,208,204)};
        if("forest".equals(theme))return new int[]{Color.rgb(12,16,14),Color.rgb(23,29,18),Color.rgb(49,48,24),Color.rgb(72,69,27),Color.rgb(219,196,74)};
        if("plum".equals(theme))return new int[]{Color.rgb(16,12,23),Color.rgb(28,20,35),Color.rgb(48,38,53),Color.rgb(61,47,64),Color.rgb(137,116,143)};
        if("copper".equals(theme))return new int[]{Color.rgb(3,19,18),Color.rgb(7,31,27),Color.rgb(52,39,27),Color.rgb(76,52,31),Color.rgb(209,134,55)};
        return new int[]{Color.rgb(7,18,25),Color.rgb(12,28,35),Color.rgb(34,49,56),Color.rgb(43,60,66),Color.rgb(239,58,66)};
    }

    static Bitmap recolour(Bitmap source,String palette){
        Bitmap out=source.copy(Bitmap.Config.ARGB_8888,true);int w=out.getWidth(),h=out.getHeight();int[] pixels=new int[w*h];out.getPixels(pixels,0,w,0,0,w,h);int[][] colours=paletteColours(palette);double[] levels={-5,10,20,35,45,55,65};
        for(int i=0;i<pixels.length;i++){int c=pixels[i],a=Color.alpha(c);if(a==0)continue;int r=Color.red(c),g=Color.green(c),b=Color.blue(c);double dbz;
            if(r>225&&g>225&&b>225)dbz=66;else if(b>180&&r>180)dbz=g>175?66:55+(170-g)*9.0/92.0;else if(r>220&&g>75&&b<80)dbz=35+(238-g)*9.0/109.0;else if(r>80&&g<90&&b<80)dbz=45+(255-r)*9.0/162.0;else if(b>g&&b>r&&g>=150)dbz=20-(g-163)*5.0/58.0;else if(b>g&&b>r)dbz=20+(163-g)*14.0/92.0;else if(r>70&&g>70&&b<180)dbz=Math.max(-5,15-(g-123)*.11);else continue;
            int upper=1;while(upper<levels.length-1&&dbz>levels[upper])upper++;int lower=upper-1;double amount=Math.max(0,Math.min(1,(dbz-levels[lower])/(levels[upper]-levels[lower])));
            pixels[i]=Color.argb(a,mix(colours[lower][0],colours[upper][0],amount),mix(colours[lower][1],colours[upper][1],amount),mix(colours[lower][2],colours[upper][2],amount));
        }
        out.setPixels(pixels,0,w,0,0,w,h);return out;
    }

    private static int mix(int a,int b,double t){return(int)Math.round(a+(b-a)*t);}
    private static int[][] paletteColours(String palette){
        if("lagoon".equals(palette))return new int[][]{{91,145,117},{79,174,131},{126,193,82},{214,199,75},{226,134,74},{190,76,113},{239,225,183}};
        if("sunset".equals(palette))return new int[][]{{112,128,103},{92,139,116},{174,143,73},{92,139,151},{177,108,69},{117,61,58},{213,177,105}};
        if("orchid".equals(palette))return new int[][]{{115,101,154},{79,135,169},{151,65,177},{211,125,65},{205,64,145},{111,65,164},{241,162,147}};
        if("polar".equals(palette))return new int[][]{{112,130,143},{137,162,178},{166,190,207},{177,184,218},{194,184,223},{220,207,231},{246,240,239}};
        return new int[][]{{82,111,109},{55,135,126},{37,101,94},{176,142,75},{181,94,65},{150,65,74},{105,58,83}};
    }
    private static double worldX(double lon,double world){return(lon+180.0)/360.0*world;}
    private static double worldY(double lat,double world){double s=Math.sin(Math.toRadians(Math.max(-85.05,Math.min(85.05,lat))));return(.5-Math.log((1+s)/(1-s))/(4*Math.PI))*world;}
}
