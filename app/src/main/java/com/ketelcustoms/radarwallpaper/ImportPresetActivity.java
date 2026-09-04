package com.ketelcustoms.radarwallpaper;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

public class ImportPresetActivity extends Activity {
    private PresetExchange.Data incoming;private RadioGroup choices;

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.rgb(11,15,18));try{incoming=PresetExchange.read(this,incomingUri());build();}catch(Exception error){showError(error.getMessage());}}

    private Uri incomingUri(){Uri uri=getIntent().getData();if(uri==null&&Intent.ACTION_SEND.equals(getIntent().getAction()))uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);if(uri==null&&getIntent().getClipData()!=null&&getIntent().getClipData().getItemCount()>0)uri=getIntent().getClipData().getItemAt(0).getUri();return uri;}

    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(28),dp(24),dp(28),dp(24));root.setBackgroundColor(Color.rgb(11,15,18));
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(dp(28),dp(24)+insets.getSystemWindowInsetTop(),dp(28),dp(24)+insets.getSystemWindowInsetBottom());return insets;});
        TextView eyebrow=text("IMPORT "+(PresetStore.MAP.equals(incoming.type)?"MAP":"RADAR")+" PRESET",14);eyebrow.setTypeface(Typeface.DEFAULT_BOLD);eyebrow.setTextColor(Color.rgb(150,171,181));root.addView(eyebrow);
        TextView title=text(incoming.name,27);title.setTypeface(Typeface.DEFAULT_BOLD);root.addView(title);
        TextView message=text("Choose which "+incoming.type+" preset this should overwrite. You can cancel without changing anything.",14);message.setTextColor(Color.rgb(174,190,197));root.addView(message);
        Preview preview=new Preview();LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(150));previewParams.setMargins(0,dp(18),0,dp(20));root.addView(preview,previewParams);
        choices=new RadioGroup(this);choices.setOrientation(RadioGroup.VERTICAL);String[] keys=PresetStore.keys(incoming.type);String current=getSharedPreferences("radar",MODE_PRIVATE).getString(PresetStore.MAP.equals(incoming.type)?"map_theme":"palette",keys[0]);
        for(int i=0;i<keys.length;i++){RadioButton option=new RadioButton(this);option.setId(View.generateViewId());option.setTag(keys[i]);option.setText("Replace “"+PresetStore.name(this,incoming.type,keys[i])+"”");option.setTextSize(16);option.setTextColor(Color.rgb(224,234,238));option.setPadding(0,dp(7),0,dp(7));choices.addView(option,new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT,dp(54)));if(keys[i].equals(current))option.setChecked(true);}
        root.addView(choices);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0,dp(18),0,0);
        Button cancel=new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v->finish());actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(54),1f));
        Button confirm=new Button(this);confirm.setText("Import preset");confirm.setOnClickListener(v->importPreset());actions.addView(confirm,new LinearLayout.LayoutParams(0,dp(54),1.35f));root.addView(actions);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);root.requestApplyInsets();
    }

    private void importPreset(){RadioButton selected=findViewById(choices.getCheckedRadioButtonId());if(selected==null){Toast.makeText(this,"Choose a preset to overwrite",Toast.LENGTH_SHORT).show();return;}PresetStore.save(this,incoming.type,(String)selected.getTag(),incoming.name,incoming.colours);Toast.makeText(this,"Imported “"+incoming.name+"”",Toast.LENGTH_LONG).show();finish();}
    private void showError(String detail){new AlertDialog.Builder(this).setTitle("Could not import preset").setMessage(detail==null?"The selected file is not a valid Radar Wallpaper preset.":detail).setPositiveButton("Close",(d,w)->finish()).setOnCancelListener(d->finish()).show();}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(224,234,238));view.setPadding(0,dp(4),0,dp(4));return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private final class Preview extends View{
        Preview(){super(ImportPresetActivity.this);}
        @Override protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight(),r=dp(12);Path clip=new Path();clip.addRoundRect(new RectF(0,0,w,h),r,r,Path.Direction.CW);int saved=canvas.save();canvas.clipPath(clip);
            if(PresetStore.RADAR.equals(incoming.type)){Paint gradient=new Paint(Paint.ANTI_ALIAS_FLAG);gradient.setShader(new LinearGradient(0,0,w,0,incoming.colours,null,Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,gradient);}else{Paint sea=new Paint(Paint.ANTI_ALIAS_FLAG);sea.setShader(new LinearGradient(0,0,0,h,incoming.colours[0],incoming.colours[1],Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,sea);Path landPath=new Path();landPath.moveTo(w*.08f,h*.82f);landPath.cubicTo(w*.22f,h*.22f,w*.58f,h*.08f,w*.91f,h*.39f);landPath.lineTo(w*.82f,h*.88f);landPath.close();Paint land=new Paint(Paint.ANTI_ALIAS_FLAG);land.setShader(new LinearGradient(0,0,0,h,incoming.colours[2],incoming.colours[3],Shader.TileMode.CLAMP));canvas.drawPath(landPath,land);Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(3));border.setColor(incoming.colours[4]);canvas.drawPath(landPath,border);}canvas.restoreToCount(saved);Paint frame=new Paint(Paint.ANTI_ALIAS_FLAG);frame.setStyle(Paint.Style.STROKE);frame.setStrokeWidth(dp(1));frame.setColor(Color.rgb(105,124,133));canvas.drawRoundRect(new RectF(.5f,.5f,w-.5f,h-.5f),r,r,frame);}
    }
}
