package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private TaiwanBackgroundView previewBackground;
    private View previewScrim;
    private ScrollView controls;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private Runnable revealPreview;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("radar", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(11,15,18));
        render();
    }

    @Override protected void onResume() { super.onResume(); if (prefs != null) render(); }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(Color.rgb(220,230,235));
        v.setPadding(0, 10, 0, 10); return v;
    }

    private void render() {
        if(previewBackground!=null)previewBackground.shutdown();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(48,48,48,32);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(48, 48 + insets.getSystemWindowInsetTop(), 48, 32 + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.setBackgroundColor(Color.TRANSPARENT);
        root.addView(text("RADAR WALLPAPER", 25));
        TextView intro = text("A quiet regional radar map that follows you. Your location stays on the phone and is used only to choose visible map tiles.", 15);
        intro.setTextColor(Color.rgb(155,171,180)); root.addView(intro);

        boolean foreground = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = android.os.Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        TextView locationStatus = text("Location: " + (!foreground ? "not allowed" : background ? "allowed all the time" : "only while app is open"), 16);
        locationStatus.setTextColor(background && foreground ? Color.rgb(130,190,165) : Color.rgb(210,175,105));
        root.addView(locationStatus);

        Button location = new Button(this); location.setText(foreground ? "Open location permission settings" : "Allow location");
        location.setOnClickListener(v -> requestLocation()); root.addView(location);
        if (foreground && !background) {
            TextView hint = text("On the next screen, choose Permissions → Location → Allow all the time. Android only shows that choice after ordinary location access has been granted.", 13);
            hint.setTextColor(Color.rgb(155,171,180)); root.addView(hint);
        }

        int currentZoom = prefs.getInt("zoom", 6);
        TextView zoomLabel = text("Regional scale: " + (currentZoom == 5 ? "wide" : currentZoom == 6 ? "regional" : "closer"), 16);
        root.addView(zoomLabel);
        SeekBar zoom = new SeekBar(this); zoom.setMax(2); zoom.setProgress(currentZoom - 5);
        zoom.setOnSeekBarChangeListener(listener(p -> { prefs.edit().putInt("zoom", p + 5).apply(); render(); })); root.addView(zoom);

        int opacityValue = prefs.getInt("opacity", 72);
        TextView opacityLabel = text("Radar opacity: " + opacityValue + "%", 16); root.addView(opacityLabel);
        SeekBar opacity = new SeekBar(this); opacity.setMax(70); opacity.setProgress(opacityValue - 30);
        opacity.setOnSeekBarChangeListener(listener(p -> { prefs.edit().putInt("opacity", p + 30).apply(); opacityLabel.setText("Radar opacity: " + (p + 30) + "%"); if(previewBackground!=null)previewBackground.showSaved(); })); root.addView(opacity);

        addSpectrumSection(root, "RADAR COLOURS", "palette", prefs.getString("palette", "night"),
                new String[]{"night","lagoon","sunset","orchid","polar"},
                new String[]{"Butts\nSocks","Rens'\nCaipirinha","Krüters\nKlarinet","Ons-Low\nICQ","Cemsto\nClean"},
                new int[][]{
                        {Color.rgb(82,111,109),Color.rgb(55,135,126),Color.rgb(37,101,94),Color.rgb(176,142,75),Color.rgb(181,94,65),Color.rgb(150,65,74),Color.rgb(105,58,83)},
                        {Color.rgb(91,145,117),Color.rgb(79,174,131),Color.rgb(126,193,82),Color.rgb(214,199,75),Color.rgb(226,134,74),Color.rgb(190,76,113),Color.rgb(239,225,183)},
                        {Color.rgb(112,128,103),Color.rgb(92,139,116),Color.rgb(174,143,73),Color.rgb(92,139,151),Color.rgb(177,108,69),Color.rgb(117,61,58),Color.rgb(213,177,105)},
                        {Color.rgb(115,101,154),Color.rgb(79,135,169),Color.rgb(151,65,177),Color.rgb(211,125,65),Color.rgb(205,64,145),Color.rgb(111,65,164),Color.rgb(241,162,147)},
                        {Color.rgb(112,130,143),Color.rgb(137,162,178),Color.rgb(166,190,207),Color.rgb(177,184,218),Color.rgb(194,184,223),Color.rgb(220,207,231),Color.rgb(246,240,239)}
                });

        addMapPreviewSection(root, "MAP COLOURS", "map_theme", prefs.getString("map_theme", "slate"),
                new String[]{"slate","navy","forest","plum","copper"},
                new String[]{"Schagchel\nStraat","Spaarne\nControl","Lange\nVeer","Du\nTheatre","Eenden\nHok"},
                new int[][]{
                        {Color.rgb(7,18,25),Color.rgb(12,28,35),Color.rgb(34,49,56),Color.rgb(43,60,66),Color.rgb(239,58,66)},
                        {Color.rgb(3,14,24),Color.rgb(7,25,38),Color.rgb(24,42,51),Color.rgb(34,57,63),Color.rgb(71,208,204)},
                        {Color.rgb(12,16,14),Color.rgb(23,29,18),Color.rgb(49,48,24),Color.rgb(72,69,27),Color.rgb(219,196,74)},
                        {Color.rgb(16,12,23),Color.rgb(28,20,35),Color.rgb(48,38,53),Color.rgb(61,47,64),Color.rgb(137,116,143)},
                        {Color.rgb(3,19,18),Color.rgb(7,31,27),Color.rgb(52,39,27),Color.rgb(76,52,31),Color.rgb(209,134,55)}
                });

        Button apply = new Button(this); apply.setText("Set live wallpaper");
        apply.setOnClickListener(v -> {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, RadarWallpaperService.class));
            startActivity(i);
        }); root.addView(apply);

        TextView credit = text("Weather data by RainViewer", 12);
        credit.setGravity(Gravity.CENTER); credit.setTextColor(Color.rgb(115,135,145));
        credit.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rainviewer.com/"))));
        root.addView(credit);
        controls = new ScrollView(this);
        controls.setFillViewport(true); controls.addView(root);
        FrameLayout page=new FrameLayout(this);previewBackground=new TaiwanBackgroundView();page.addView(previewBackground,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        previewScrim=new View(this);previewScrim.setBackgroundColor(Color.BLACK);previewScrim.setAlpha(.64f);page.addView(previewScrim,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(controls,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));setContentView(page);
        root.requestApplyInsets();
    }

    private SeekBar.OnSeekBarChangeListener listener(java.util.function.IntConsumer done) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { done.accept(b.getProgress()); }
        };
    }

    private void addSpectrumSection(LinearLayout root,String title,String preference,String selected,String[] keys,String[] names,int[][] colours) {
        TextView heading=text(title,15);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setPadding(0,dp(22),0,dp(6));root.addView(heading);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout[] cards=new LinearLayout[keys.length];TextView[] labels=new TextView[keys.length];GradientDrawable[] swatches=new GradientDrawable[keys.length];
        for(int i=0;i<keys.length;i++) {
            boolean active=keys[i].equals(selected);
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(3),dp(3),dp(3),dp(5));card.setClickable(true);
            TextView name=text(names[i],11);name.setGravity(Gravity.CENTER);name.setMinLines(2);name.setMaxLines(2);
            card.addView(name,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)));
            View spectrum=new View(this);GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colours[i]);
            gradient.setCornerRadius(dp(7));spectrum.setBackground(gradient);
            LinearLayout.LayoutParams spectrumParams=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(122));spectrumParams.setMargins(dp(5),0,dp(5),dp(5));card.addView(spectrum,spectrumParams);
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(0,dp(184),1f);cardParams.setMargins(dp(2),0,dp(2),0);row.addView(card,cardParams);
            cards[i]=card;labels[i]=name;swatches[i]=gradient;styleSpectrumCard(card,name,gradient,active);
        }
        for(int i=0;i<keys.length;i++) {
            final int chosen=i;
            cards[i].setOnClickListener(v->{
                prefs.edit().putString(preference,keys[chosen]).apply();
                for(int j=0;j<cards.length;j++)styleSpectrumCard(cards[j],labels[j],swatches[j],j==chosen);
                if(previewBackground!=null)previewBackground.showSaved();
            });
            attachHoldPreview(cards[i],preference,keys[i]);
        }
        root.addView(row,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(184)));
    }

    private void styleSpectrumCard(LinearLayout card,TextView name,GradientDrawable spectrum,boolean active) {
        GradientDrawable background=new GradientDrawable();background.setColor(active?Color.rgb(31,46,53):Color.TRANSPARENT);background.setCornerRadius(dp(9));card.setBackground(background);
        name.setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);name.setTextColor(active?Color.rgb(229,244,248):Color.rgb(155,171,180));
        spectrum.setStroke(dp(active?3:1),active?Color.rgb(226,244,249):Color.rgb(67,84,92));
    }

    private void addMapPreviewSection(LinearLayout root,String title,String preference,String selected,String[] keys,String[] names,int[][] colours) {
        TextView heading=text(title,15);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setPadding(0,dp(22),0,dp(6));root.addView(heading);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout[] cards=new LinearLayout[keys.length];TextView[] labels=new TextView[keys.length];MapPreviewView[] previews=new MapPreviewView[keys.length];
        for(int i=0;i<keys.length;i++) {
            boolean active=keys[i].equals(selected);
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(3),dp(3),dp(3),dp(5));card.setClickable(true);
            TextView name=text(names[i],11);name.setGravity(Gravity.CENTER);name.setMinLines(2);name.setMaxLines(2);
            card.addView(name,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)));
            MapPreviewView preview=new MapPreviewView(colours[i]);
            LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(122));previewParams.setMargins(dp(5),0,dp(5),dp(5));card.addView(preview,previewParams);
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(0,dp(184),1f);cardParams.setMargins(dp(2),0,dp(2),0);row.addView(card,cardParams);
            cards[i]=card;labels[i]=name;previews[i]=preview;styleMapCard(card,name,preview,active);
        }
        for(int i=0;i<keys.length;i++) {
            final int chosen=i;
            cards[i].setOnClickListener(v->{
                prefs.edit().putString(preference,keys[chosen]).apply();
                for(int j=0;j<cards.length;j++)styleMapCard(cards[j],labels[j],previews[j],j==chosen);
                if(previewBackground!=null)previewBackground.showSaved();
            });
            attachHoldPreview(cards[i],preference,keys[i]);
        }
        root.addView(row,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(184)));
    }

    private void styleMapCard(LinearLayout card,TextView name,MapPreviewView preview,boolean active) {
        GradientDrawable background=new GradientDrawable();background.setColor(active?Color.rgb(31,46,53):Color.TRANSPARENT);background.setCornerRadius(dp(9));card.setBackground(background);
        name.setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);name.setTextColor(active?Color.rgb(229,244,248):Color.rgb(155,171,180));
        preview.setActive(active);
    }

    private final class MapPreviewView extends View {
        private final int[] theme;private boolean active;
        private final float[][] zealand={
                {12.569f,55.785f},{12.545f,55.656f},{12.321f,55.588f},{12.243f,55.538f},{12.215f,55.467f},{12.385f,55.386f},
                {12.413f,55.286f},{11.654f,55.187f},{11.286f,55.204f},{11.171f,55.329f},{11.190f,55.466f},{11.121f,55.601f},{11.009f,55.644f},{10.979f,55.722f},
                {11.322f,55.753f},{11.464f,55.879f},{11.475f,55.943f},{11.628f,55.957f},{11.696f,55.908f},{11.691f,55.729f},
                {11.820f,55.698f},{11.935f,55.896f},{11.866f,55.968f},{12.219f,56.119f},{12.579f,56.064f},{12.608f,56.033f},
                {12.525f,55.918f}
        };
        MapPreviewView(int[] colours){super(SettingsActivity.this);theme=colours;}
        void setActive(boolean value){active=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float w=getWidth(),h=getHeight(),radius=dp(7),inset=dp(active?3:1);
            Path clip=new Path();clip.addRoundRect(new RectF(inset,inset,w-inset,h-inset),radius,radius,Path.Direction.CW);
            int save=canvas.save();canvas.clipPath(clip);
            Paint ocean=new Paint(Paint.ANTI_ALIAS_FLAG);ocean.setShader(new LinearGradient(0,0,0,h,theme[0],theme[1],Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,ocean);
            float minLon=10.85f,maxLon=12.75f,minLat=55.10f,maxLat=56.20f,cos=0.566f,pad=dp(8);
            float geoW=(maxLon-minLon)*cos,geoH=maxLat-minLat,scale=Math.min((w-2*pad)/geoW,(h-2*pad)/geoH);
            float left=(w-geoW*scale)/2f,top=(h-geoH*scale)/2f;Path landPath=new Path();
            for(int i=0;i<zealand.length;i++){
                float x=left+(zealand[i][0]-minLon)*cos*scale,y=top+(maxLat-zealand[i][1])*scale;
                if(i==0)landPath.moveTo(x,y);else landPath.lineTo(x,y);
            }
            landPath.close();Paint land=new Paint(Paint.ANTI_ALIAS_FLAG);land.setShader(new LinearGradient(0,0,0,h,theme[2],theme[3],Shader.TileMode.CLAMP));canvas.drawPath(landPath,land);
            Paint coast=new Paint(Paint.ANTI_ALIAS_FLAG);coast.setStyle(Paint.Style.STROKE);coast.setStrokeWidth(dp(1));coast.setColor(theme[4]);canvas.drawPath(landPath,coast);
            canvas.restoreToCount(save);
            Paint frame=new Paint(Paint.ANTI_ALIAS_FLAG);frame.setStyle(Paint.Style.STROKE);frame.setStrokeWidth(dp(active?3:1));frame.setColor(active?Color.rgb(226,244,249):Color.rgb(67,84,92));
            canvas.drawRoundRect(new RectF(inset,inset,w-inset,h-inset),radius,radius,frame);
        }
    }

    private int dp(int value) {
        return Math.round(value*getResources().getDisplayMetrics().density);
    }

    private void attachHoldPreview(View card,String preference,String key){
        card.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                if(previewBackground!=null)previewBackground.showCandidate(preference,key);
                revealPreview=()->{if(controls!=null)controls.animate().alpha(0f).setDuration(120).start();if(previewScrim!=null)previewScrim.animate().alpha(0f).setDuration(120).start();};
                mainHandler.postDelayed(revealPreview,420);
            }else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                if(revealPreview!=null)mainHandler.removeCallbacks(revealPreview);
                if(controls!=null){controls.animate().cancel();controls.setAlpha(1f);}if(previewScrim!=null){previewScrim.animate().cancel();previewScrim.setAlpha(.64f);}
                if(previewBackground!=null)previewBackground.showSaved();
            }
            return false;
        });
    }

    private final class TaiwanBackgroundView extends View{
        private final HandlerThread thread;private final Handler worker;private Bitmap image;private int generation;private String temporaryPreference,temporaryKey;
        TaiwanBackgroundView(){super(SettingsActivity.this);thread=new HandlerThread("settings-map-preview");thread.start();worker=new Handler(thread.getLooper());}
        void showCandidate(String preference,String key){temporaryPreference=preference;temporaryKey=key;requestImage();}
        void showSaved(){temporaryPreference=null;temporaryKey=null;requestImage();}
        void shutdown(){generation++;worker.removeCallbacksAndMessages(null);thread.quitSafely();}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){requestImage();}
        private void requestImage(){
            int w=getWidth(),h=getHeight();if(w<1||h<1)return;int request=++generation,zoom=prefs.getInt("zoom",6),opacity=prefs.getInt("opacity",72);
            String map=prefs.getString("map_theme","slate"),palette=prefs.getString("palette","night");
            if("map_theme".equals(temporaryPreference))map=temporaryKey;if("palette".equals(temporaryPreference))palette=temporaryKey;
            final String chosenMap=map,chosenPalette=palette;worker.post(()->{Bitmap next=TaiwanPreviewRenderer.render(getApplicationContext(),w,h,zoom,chosenMap,chosenPalette,opacity);mainHandler.post(()->{if(request!=generation){next.recycle();return;}Bitmap old=image;image=next;invalidate();if(old!=null&&!old.isRecycled())old.recycle();});});
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(image!=null&&!image.isRecycled())canvas.drawBitmap(image,null,new Rect(0,0,getWidth(),getHeight()),null);else canvas.drawColor(Color.rgb(7,16,22));}
    }

    @Override protected void onDestroy(){if(previewBackground!=null)previewBackground.shutdown();mainHandler.removeCallbacksAndMessages(null);super.onDestroy();}

    private void requestLocation() {
        boolean foreground = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!foreground)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 20);
        else if (android.os.Build.VERSION.SDK_INT == 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 21);
        else {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        render();
    }
}
