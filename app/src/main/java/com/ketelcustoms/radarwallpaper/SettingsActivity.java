package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.provider.CalendarContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

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
        TextView intro = text("A quiet regional radar map that follows you. Your location stays on the phone and is used only to choose visible map tiles. Optional Flight Trails reads only the calendar you choose; its entries stay on the phone.", 15);
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

        TextView editHint=text("Double-tap a preset to edit its name and colours. Press and hold to inspect it full screen.",13);editHint.setTextColor(Color.rgb(154,174,183));root.addView(editHint);

        String[] radarKeys=PresetStore.keys(PresetStore.RADAR);
        addSpectrumSection(root,"RADAR COLOURS","palette",prefs.getString("palette","night"),radarKeys,presetNames(PresetStore.RADAR,radarKeys),presetColours(PresetStore.RADAR,radarKeys));

        String[] mapKeys=PresetStore.keys(PresetStore.MAP);
        addMapPreviewSection(root,"MAP COLOURS","map_theme",prefs.getString("map_theme","slate"),mapKeys,presetNames(PresetStore.MAP,mapKeys),presetColours(PresetStore.MAP,mapKeys));

        addFlightTrailsSection(root);

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
        TextView airportCredit = text("Airport coordinates by OurAirports", 12);
        airportCredit.setGravity(Gravity.CENTER); airportCredit.setTextColor(Color.rgb(115,135,145));
        airportCredit.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ourairports.com/data/"))));
        root.addView(airportCredit);
        controls = new ScrollView(this);
        controls.setFillViewport(true); controls.addView(root);
        FrameLayout page=new FrameLayout(this);previewBackground=new TaiwanBackgroundView();page.addView(previewBackground,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        previewScrim=new View(this);previewScrim.setBackgroundColor(Color.BLACK);previewScrim.setAlpha(.64f);page.addView(previewScrim,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(controls,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));setContentView(page);
        root.requestApplyInsets();
    }

    private void addFlightTrailsSection(LinearLayout root){
        TextView heading=text("FLIGHT TRAILS",15);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setPadding(0,dp(24),0,dp(4));root.addView(heading);
        TextView explanation=text("Draws routes from one selected calendar: seven days into the past and seven days ahead. Past routes use their recorded OpenSky track when available and otherwise remain great circles. Future routes are always great circles.",13);explanation.setTextColor(Color.rgb(154,174,183));root.addView(explanation);
        boolean allowed=checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED;boolean enabled=prefs.getBoolean("flight_trails",false);long selectedId=prefs.getLong("flight_calendar_id",-1);
        CheckBox toggle=new CheckBox(this);toggle.setText("Show flight trails");toggle.setTextSize(16);toggle.setTextColor(Color.rgb(220,230,235));toggle.setChecked(enabled);toggle.setOnCheckedChangeListener((button,checked)->{prefs.edit().putBoolean("flight_trails",checked).apply();if(checked&&!allowed)requestPermissions(new String[]{Manifest.permission.READ_CALENDAR},30);else render();});root.addView(toggle);
        TextView status=text(!allowed?"Calendar access: not allowed":selectedId<0?"Calendar: none selected":"Calendar: "+prefs.getString("flight_calendar_name","Selected calendar"),14);status.setTextColor(allowed&&selectedId>=0?Color.rgb(130,190,165):Color.rgb(210,175,105));root.addView(status);
        Button calendar=new Button(this);calendar.setText(allowed?"Choose calendar":"Allow calendar access");calendar.setOnClickListener(v->{if(!allowed)requestPermissions(new String[]{Manifest.permission.READ_CALENDAR},30);else chooseCalendar();});root.addView(calendar);
        if(enabled&&allowed&&selectedId>=0){TextView found=text("Routes recognised now: "+FlightCalendar.count(this,prefs),13);found.setTextColor(Color.rgb(154,174,183));root.addView(found);}
        int weight=prefs.getInt("flight_trail_width",2);TextView weightLabel=text("Flight-line weight: "+(weight==1?"very fine":weight==2?"fine":weight==3?"medium":"bold"),14);root.addView(weightLabel);
        SeekBar width=new SeekBar(this);width.setMax(3);width.setProgress(weight-1);width.setOnSeekBarChangeListener(listener(p->{int chosen=p+1;prefs.edit().putInt("flight_trail_width",chosen).apply();weightLabel.setText("Flight-line weight: "+(chosen==1?"very fine":chosen==2?"fine":chosen==3?"medium":"bold"));}));root.addView(width);
        Button colour=new Button(this);colour.setText("Trail colour");colour.setOnClickListener(v->chooseTrailColour());root.addView(colour);
        addOpenSkySection(root,enabled&&allowed&&selectedId>=0);
    }

    private void addOpenSkySection(LinearLayout root,boolean calendarReady){
        TextView heading=text("ACTUAL PAST TRACKS",14);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setPadding(0,dp(18),0,dp(2));root.addView(heading);
        TextView explanation=text("Optional OpenSky lookup for completed flights. Your API credentials stay in this app's private storage, tracks are saved on this phone, and unmatched flights keep their great-circle line.",13);explanation.setTextColor(Color.rgb(154,174,183));root.addView(explanation);
        boolean enabled=prefs.getBoolean("opensky_actual_tracks",false),configured=!prefs.getString("opensky_client_id","").isEmpty()&&!prefs.getString("opensky_client_secret","").isEmpty();
        CheckBox actual=new CheckBox(this);actual.setText("Use recorded OpenSky tracks");actual.setTextSize(16);actual.setTextColor(Color.rgb(220,230,235));actual.setChecked(enabled);actual.setOnCheckedChangeListener((button,checked)->{prefs.edit().putBoolean("opensky_actual_tracks",checked).apply();if(checked&&!configured)mainHandler.postDelayed(this::editOpenSkyCredentials,100);});root.addView(actual);
        TextView status=text(configured?"OpenSky credentials: saved on this phone":"OpenSky credentials: not configured",13);status.setTextColor(configured?Color.rgb(130,190,165):Color.rgb(210,175,105));root.addView(status);
        if(calendarReady){List<FlightCalendar.Leg> legs=FlightCalendar.load(this,prefs,System.currentTimeMillis());int exact=OpenSkyTracks.exactCount(this,legs),borrowed=OpenSkyTracks.borrowedCount(this,legs);TextView cached=text("Tracks cached: "+exact+" exact, "+borrowed+" borrowed from an earlier flight",13);cached.setTextColor(Color.rgb(154,174,183));root.addView(cached);}
        Button credentials=new Button(this);credentials.setText(configured?"Edit OpenSky credentials":"Enter OpenSky credentials");credentials.setOnClickListener(v->editOpenSkyCredentials());root.addView(credentials);
        Button account=new Button(this);account.setText("Open free OpenSky account page");account.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://opensky-network.org/my-opensky/account"))));root.addView(account);
        if(configured){Button forget=new Button(this);forget.setText("Forget OpenSky credentials");forget.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("Forget credentials?").setMessage("Downloaded tracks will remain cached, but no new actual tracks will be fetched.").setPositiveButton("Forget",(dialog,which)->{prefs.edit().remove("opensky_client_id").remove("opensky_client_secret").putBoolean("opensky_actual_tracks",false).apply();render();}).setNegativeButton("Cancel",null).show());root.addView(forget);}
    }

    private void editOpenSkyCredentials(){
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),0,dp(24),0);
        EditText client=new EditText(this);client.setHint("Client ID");client.setSingleLine(true);client.setText(prefs.getString("opensky_client_id",""));fields.addView(client);
        EditText secret=new EditText(this);secret.setHint(prefs.getString("opensky_client_secret","").isEmpty()?"Client secret":"Client secret (leave blank to keep saved value)");secret.setSingleLine(true);secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);fields.addView(secret);
        new android.app.AlertDialog.Builder(this).setTitle("OpenSky API credentials").setMessage("Create a free API Client on your OpenSky account page, then paste its client ID and client secret here.").setView(fields).setPositiveButton("Save",(dialog,which)->{String id=client.getText().toString().trim(),newSecret=secret.getText().toString().trim(),chosenSecret=newSecret.isEmpty()?prefs.getString("opensky_client_secret",""):newSecret;prefs.edit().putString("opensky_client_id",id).putString("opensky_client_secret",chosenSecret).putBoolean("opensky_actual_tracks",!id.isEmpty()&&!chosenSecret.isEmpty()).apply();render();}).setNegativeButton("Cancel",null).show();
    }

    private void chooseCalendar(){
        if(checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)return;ArrayList<Long> ids=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();String[] projection={CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,CalendarContract.Calendars.ACCOUNT_NAME};
        try(Cursor cursor=getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,projection,CalendarContract.Calendars.VISIBLE+"=1",null,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME+" COLLATE NOCASE")){if(cursor!=null)while(cursor.moveToNext()){ids.add(cursor.getLong(0));String name=cursor.getString(1),account=cursor.getString(2);labels.add(name+(account==null||account.equals(name)?"":" — "+account));}}catch(Exception ignored){}
        if(ids.isEmpty()){new android.app.AlertDialog.Builder(this).setTitle("No calendars found").setMessage("Make sure the calendar is visible and synchronised in your Android calendar app.").setPositiveButton("Close",null).show();return;}
        new android.app.AlertDialog.Builder(this).setTitle("Calendar containing flights").setItems(labels.toArray(new String[0]),(dialog,which)->{prefs.edit().putLong("flight_calendar_id",ids.get(which)).putString("flight_calendar_name",labels.get(which)).putBoolean("flight_trails",true).apply();render();}).setNegativeButton("Cancel",null).show();
    }

    private void chooseTrailColour(){
        String[] names={"Glacier","Mint","Lavender","Amber","Coral"};int[] colours={Color.rgb(126,207,214),Color.rgb(133,211,169),Color.rgb(177,159,222),Color.rgb(224,183,105),Color.rgb(224,130,121)};
        new android.app.AlertDialog.Builder(this).setTitle("Flight-trail colour").setItems(names,(dialog,which)->prefs.edit().putInt("flight_trail_color",colours[which]).apply()).setNegativeButton("Cancel",null).show();
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
        LinearLayout[] cards=new LinearLayout[keys.length];TextView[] labels=new TextView[keys.length];GradientDrawable[] swatches=new GradientDrawable[keys.length];long[] lastTap=new long[keys.length];
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
                long now=System.currentTimeMillis();if(now-lastTap[chosen]<380){lastTap[chosen]=0;openPresetEditor(PresetStore.RADAR,keys[chosen]);return;}lastTap[chosen]=now;
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
        LinearLayout[] cards=new LinearLayout[keys.length];TextView[] labels=new TextView[keys.length];MapPreviewView[] previews=new MapPreviewView[keys.length];long[] lastTap=new long[keys.length];
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
                long now=System.currentTimeMillis();if(now-lastTap[chosen]<380){lastTap[chosen]=0;openPresetEditor(PresetStore.MAP,keys[chosen]);return;}lastTap[chosen]=now;
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

    private String[] presetNames(String type,String[] keys){String[] names=new String[keys.length];for(int i=0;i<keys.length;i++)names[i]=PresetStore.cardName(PresetStore.name(this,type,keys[i]));return names;}
    private int[][] presetColours(String type,String[] keys){int[][] colours=new int[keys.length][];for(int i=0;i<keys.length;i++)colours[i]=PresetStore.colours(this,type,keys[i]);return colours;}
    private void openPresetEditor(String type,String key){Intent intent=new Intent(this,PresetEditorActivity.class);intent.putExtra("type",type);intent.putExtra("key",key);startActivity(intent);}

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
        if(requestCode==30&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){render();mainHandler.postDelayed(this::chooseCalendar,150);}else render();
    }
}
