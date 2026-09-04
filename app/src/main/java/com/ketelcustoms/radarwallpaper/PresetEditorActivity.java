package com.ketelcustoms.radarwallpaper;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

public class PresetEditorActivity extends Activity {
    private String type,key;private int[] colours;private EditText name;private PresetPreview preview;private LinearLayout stops;
    private final ArrayList<View> swatches=new ArrayList<>();private final ArrayList<TextView> values=new ArrayList<>();
    private static final String[] RADAR_LABELS={"Drizzle","Light","Moderate","Heavy","Downpour","Extreme","Hail"};
    private static final String[] MAP_LABELS={"Land","Sea","Borders"};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);type=getIntent().getStringExtra("type");key=getIntent().getStringExtra("key");if(!PresetStore.MAP.equals(type))type=PresetStore.RADAR;
        colours=PresetStore.colours(this,type,key);getWindow().setStatusBarColor(Color.rgb(11,15,18));build();
    }

    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(28),dp(24),dp(28),dp(24));root.setBackgroundColor(Color.rgb(11,15,18));
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(dp(28),dp(24)+insets.getSystemWindowInsetTop(),dp(28),dp(24)+insets.getSystemWindowInsetBottom());return insets;});
        TextView title=text("EDIT PRESET",14);title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextColor(Color.rgb(150,171,181));root.addView(title);
        name=new EditText(this);name.setSingleLine(true);name.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);name.setText(PresetStore.name(this,type,key));name.setTextSize(25);name.setTextColor(Color.WHITE);name.setHint("Preset name");name.setHintTextColor(Color.rgb(112,130,140));root.addView(name,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(64)));
        TextView hint=text(PresetStore.MAP.equals(type)?"Choose the main land, sea and border colours. The map adds subtle shading automatically.":"Tap any intensity stop to change its colour.",14);hint.setTextColor(Color.rgb(174,190,197));root.addView(hint);
        LinearLayout editor=new LinearLayout(this);editor.setOrientation(LinearLayout.HORIZONTAL);editor.setPadding(0,dp(16),0,dp(16));
        preview=new PresetPreview();editor.addView(preview,new LinearLayout.LayoutParams(0,dp(430),.42f));
        stops=new LinearLayout(this);stops.setOrientation(LinearLayout.VERTICAL);stops.setPadding(dp(18),0,0,0);editor.addView(stops,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,.58f));root.addView(editor);
        rebuildStops();
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel=new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v->finish());actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(54),1f));
        Button reset=new Button(this);reset.setText("Reset");reset.setOnClickListener(v->{colours=PresetStore.defaultColours(type,key);name.setText(PresetStore.defaultName(type,key));rebuildStops();preview.invalidate();});actions.addView(reset,new LinearLayout.LayoutParams(0,dp(54),1f));
        root.addView(actions);
        LinearLayout primaryActions=new LinearLayout(this);primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        Button share=new Button(this);share.setText(PresetStore.MAP.equals(type)?"Share map":"Share radar");share.setOnClickListener(v->sharePreset());primaryActions.addView(share,new LinearLayout.LayoutParams(0,dp(54),1f));
        Button save=new Button(this);save.setText("Save");save.setOnClickListener(v->{String chosen=validName();if(chosen==null)return;PresetStore.save(this,type,key,chosen,colours);setResult(RESULT_OK);finish();});primaryActions.addView(save,new LinearLayout.LayoutParams(0,dp(54),1f));root.addView(primaryActions);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);root.requestApplyInsets();
    }

    private String validName(){String chosen=name.getText().toString().trim();if(chosen.isEmpty()){name.setError("Enter a name");return null;}return chosen;}
    private void sharePreset(){String chosen=validName();if(chosen==null)return;try{Uri file=PresetExchange.writeSharedFile(this,type,chosen,colours);Intent send=new Intent(Intent.ACTION_SEND);send.setType(PresetExchange.mime(type));send.putExtra(Intent.EXTRA_STREAM,file);send.putExtra(Intent.EXTRA_SUBJECT,chosen+" — Radar Wallpaper preset");send.setClipData(ClipData.newRawUri("Radar Wallpaper preset",file));send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"Share "+(PresetStore.MAP.equals(type)?"map":"radar")+" preset"));}catch(Exception error){Toast.makeText(this,"Could not share preset: "+error.getMessage(),Toast.LENGTH_LONG).show();}}

    private void rebuildStops(){
        stops.removeAllViews();swatches.clear();values.clear();int[] shown=PresetStore.MAP.equals(type)?PresetStore.mapEditorColours(colours):colours;String[] labels=PresetStore.MAP.equals(type)?MAP_LABELS:RADAR_LABELS;
        for(int i=0;i<shown.length;i++){final int position=i;LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(3),0,dp(3));
            View swatch=new View(this);setSwatch(swatch,shown[i]);row.addView(swatch,new LinearLayout.LayoutParams(dp(42),dp(42)));
            LinearLayout wording=new LinearLayout(this);wording.setOrientation(LinearLayout.VERTICAL);wording.setPadding(dp(12),0,0,0);TextView label=text(labels[i],14);label.setTypeface(Typeface.DEFAULT_BOLD);TextView value=text(hex(shown[i]),12);value.setTextColor(Color.rgb(148,167,176));wording.addView(label);wording.addView(value);row.addView(wording,new LinearLayout.LayoutParams(0,dp(PresetStore.MAP.equals(type)?74:54),1f));
            row.setClickable(true);row.setOnClickListener(v->openColourPicker(position));stops.addView(row);swatches.add(swatch);values.add(value);
        }
    }

    private void openColourPicker(int position){
        int initial=PresetStore.MAP.equals(type)?PresetStore.mapEditorColours(colours)[position]:colours[position];float[] hsv=new float[3];Color.colorToHSV(initial,hsv);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),0);View sample=new View(this);box.addView(sample,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(72)));TextView code=text("",14);code.setGravity(Gravity.CENTER);box.addView(code);
        SeekBar hue=slider(box,"Hue",360,Math.round(hsv[0])),saturation=slider(box,"Saturation",100,Math.round(hsv[1]*100)),brightness=slider(box,"Brightness",100,Math.round(hsv[2]*100));
        final int[] selected={initial};Runnable update=()->{selected[0]=Color.HSVToColor(new float[]{hue.getProgress(),saturation.getProgress()/100f,brightness.getProgress()/100f});sample.setBackgroundColor(selected[0]);code.setText(hex(selected[0]));};
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){update.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};hue.setOnSeekBarChangeListener(listener);saturation.setOnSeekBarChangeListener(listener);brightness.setOnSeekBarChangeListener(listener);update.run();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle((PresetStore.MAP.equals(type)?MAP_LABELS:RADAR_LABELS)[position]).setView(box).setNegativeButton("Cancel",null).setPositiveButton("Use colour",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(PresetStore.MAP.equals(type))PresetStore.setMapEditorColour(colours,position,selected[0]);else colours[position]=selected[0];rebuildStops();preview.invalidate();dialog.dismiss();}));dialog.show();
    }

    private SeekBar slider(LinearLayout parent,String label,int max,int progress){TextView text=text(label,13);parent.addView(text);SeekBar bar=new SeekBar(this);bar.setMax(max);bar.setProgress(progress);parent.addView(bar,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(42)));return bar;}
    private void setSwatch(View view,int colour){GradientDrawable background=new GradientDrawable();background.setColor(colour);background.setCornerRadius(dp(9));background.setStroke(dp(1),Color.rgb(190,205,211));view.setBackground(background);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(224,234,238));view.setPadding(0,dp(3),0,dp(3));return view;}
    private String hex(int colour){return String.format(Locale.US,"#%06X",0xFFFFFF&colour);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private final class PresetPreview extends View{
        PresetPreview(){super(PresetEditorActivity.this);}
        @Override protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight(),radius=dp(13);Path clip=new Path();clip.addRoundRect(new RectF(0,0,w,h),radius,radius,Path.Direction.CW);int save=canvas.save();canvas.clipPath(clip);
            if(PresetStore.MAP.equals(type)){Paint sea=new Paint(Paint.ANTI_ALIAS_FLAG);sea.setShader(new LinearGradient(0,0,0,h,colours[0],colours[1],Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,sea);Path landPath=new Path();landPath.moveTo(w*.14f,h*.82f);landPath.cubicTo(w*.08f,h*.55f,w*.31f,h*.19f,w*.59f,h*.12f);landPath.cubicTo(w*.84f,h*.27f,w*.72f,h*.48f,w*.92f,h*.68f);landPath.cubicTo(w*.73f,h*.89f,w*.42f,h*.76f,w*.14f,h*.82f);landPath.close();Paint land=new Paint(Paint.ANTI_ALIAS_FLAG);land.setShader(new LinearGradient(0,0,0,h,colours[2],colours[3],Shader.TileMode.CLAMP));canvas.drawPath(landPath,land);Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(4));border.setColor(colours[4]);canvas.drawPath(landPath,border);}
            else{Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setShader(new LinearGradient(0,0,0,h,colours,null,Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,paint);}
            canvas.restoreToCount(save);Paint edge=new Paint(Paint.ANTI_ALIAS_FLAG);edge.setStyle(Paint.Style.STROKE);edge.setStrokeWidth(dp(1));edge.setColor(Color.rgb(105,124,133));canvas.drawRoundRect(new RectF(.5f,.5f,w-.5f,h-.5f),radius,radius,edge);
        }
    }
}
