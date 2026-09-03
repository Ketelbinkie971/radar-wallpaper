package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private SharedPreferences prefs;

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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(48,48,48,32);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(48, 48 + insets.getSystemWindowInsetTop(), 48, 32 + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.setBackgroundColor(Color.rgb(11,15,18));
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
        opacity.setOnSeekBarChangeListener(listener(p -> { prefs.edit().putInt("opacity", p + 30).apply(); opacityLabel.setText("Radar opacity: " + (p + 30) + "%"); })); root.addView(opacity);

        addSpectrumSection(root, "RADAR COLOURS", "palette", prefs.getString("palette", "wu"),
                new String[]{"blue","wu","wu_classic","night"},
                new String[]{"Universal\nBlue","Muted WU\nStorm","WU\nStorm","KetelCalm"},
                new int[][]{
                        {Color.rgb(136,221,238),Color.rgb(0,163,224),Color.rgb(0,71,104),Color.rgb(255,238,0),Color.rgb(255,68,0),Color.rgb(255,170,255)},
                        {Color.rgb(55,101,83),Color.rgb(40,128,91),Color.rgb(23,103,76),Color.rgb(190,154,52),Color.rgb(194,74,48),Color.rgb(142,48,105)},
                        {Color.rgb(0,196,119),Color.rgb(0,163,92),Color.rgb(0,111,57),Color.rgb(255,188,0),Color.rgb(255,68,0),Color.rgb(224,0,126)},
                        {Color.rgb(78,112,111),Color.rgb(55,139,132),Color.rgb(35,103,99),Color.rgb(184,145,76),Color.rgb(188,88,70),Color.rgb(119,72,119)}
                });

        addSpectrumSection(root, "MAP COLOURS", "map_theme", prefs.getString("map_theme", "slate"),
                new String[]{"slate","navy","forest","plum"},
                new String[]{"Schagchel\nSlate","Midnight\nNavy","Forest\nCharcoal","Plum\nDusk"},
                new int[][]{
                        {Color.rgb(7,18,25),Color.rgb(12,28,35),Color.rgb(34,49,56),Color.rgb(43,60,66),Color.rgb(103,125,134)},
                        {Color.rgb(4,15,25),Color.rgb(8,28,42),Color.rgb(25,43,55),Color.rgb(38,59,70),Color.rgb(104,135,149)},
                        {Color.rgb(7,18,19),Color.rgb(13,29,28),Color.rgb(32,48,43),Color.rgb(44,61,52),Color.rgb(111,133,119)},
                        {Color.rgb(16,12,23),Color.rgb(28,20,35),Color.rgb(48,38,53),Color.rgb(61,47,64),Color.rgb(137,116,143)}
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(root);
        setContentView(scroll);
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
            });
        }
        root.addView(row,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(184)));
    }

    private void styleSpectrumCard(LinearLayout card,TextView name,GradientDrawable spectrum,boolean active) {
        GradientDrawable background=new GradientDrawable();background.setColor(active?Color.rgb(31,46,53):Color.TRANSPARENT);background.setCornerRadius(dp(9));card.setBackground(background);
        name.setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);name.setTextColor(active?Color.rgb(229,244,248):Color.rgb(155,171,180));
        spectrum.setStroke(dp(active?3:1),active?Color.rgb(226,244,249):Color.rgb(67,84,92));
    }

    private int dp(int value) {
        return Math.round(value*getResources().getDisplayMetrics().density);
    }

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
