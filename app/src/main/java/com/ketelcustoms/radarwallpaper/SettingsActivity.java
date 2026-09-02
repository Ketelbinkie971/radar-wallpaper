package com.ketelcustoms.radarwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("radar", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(11,15,18));
        render();
    }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(Color.rgb(220,230,235));
        v.setPadding(0, 10, 0, 10); return v;
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(48,48,48,32);
        root.setBackgroundColor(Color.rgb(11,15,18));
        root.addView(text("RADAR WALLPAPER", 25));
        TextView intro = text("A quiet regional radar map that follows you. Your location stays on the phone and is used only to choose visible map tiles.", 15);
        intro.setTextColor(Color.rgb(155,171,180)); root.addView(intro);

        Button location = new Button(this); location.setText("Allow location");
        location.setOnClickListener(v -> requestLocation()); root.addView(location);

        int currentZoom = prefs.getInt("zoom", 6);
        TextView zoomLabel = text("Regional scale: " + (currentZoom == 5 ? "wide" : currentZoom == 6 ? "regional" : "closer"), 16);
        root.addView(zoomLabel);
        SeekBar zoom = new SeekBar(this); zoom.setMax(2); zoom.setProgress(currentZoom - 5);
        zoom.setOnSeekBarChangeListener(listener(p -> { prefs.edit().putInt("zoom", p + 5).apply(); render(); })); root.addView(zoom);

        int opacityValue = prefs.getInt("opacity", 72);
        TextView opacityLabel = text("Radar opacity: " + opacityValue + "%", 16); root.addView(opacityLabel);
        SeekBar opacity = new SeekBar(this); opacity.setMax(70); opacity.setProgress(opacityValue - 30);
        opacity.setOnSeekBarChangeListener(listener(p -> { prefs.edit().putInt("opacity", p + 30).apply(); opacityLabel.setText("Radar opacity: " + (p + 30) + "%"); })); root.addView(opacity);

        Button apply = new Button(this); apply.setText("Set live wallpaper");
        apply.setOnClickListener(v -> {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, RadarWallpaperService.class));
            startActivity(i);
        }); root.addView(apply);

        TextView credit = text("Weather data by RainViewer  •  Map © OpenStreetMap contributors", 12);
        credit.setGravity(Gravity.CENTER); credit.setTextColor(Color.rgb(115,135,145));
        credit.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rainviewer.com/"))));
        root.addView(credit);
        setContentView(root);
    }

    private SeekBar.OnSeekBarChangeListener listener(java.util.function.IntConsumer done) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { done.accept(b.getProgress()); }
        };
    }

    private void requestLocation() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 20);
        else startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }
}
