package com.ketelcustoms.radarwallpaper;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public class PresetFileProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){String name=uri.getLastPathSegment();if(name!=null&&name.endsWith(".map.json"))return PresetExchange.MIME_MAP;if(name!=null&&name.endsWith(".radar.json"))return PresetExchange.MIME_RADAR;return PresetExchange.MIME_JSON;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sortOrder){File file=resolve(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);MatrixCursor.RowBuilder row=cursor.newRow();for(String column:columns){if(OpenableColumns.DISPLAY_NAME.equals(column))row.add(file.getName());else if(OpenableColumns.SIZE.equals(column))row.add(file.length());else row.add(null);}return cursor;}
    private File resolve(Uri uri){String name=uri.getLastPathSegment();if(name==null||name.contains("/")||name.contains("\\")||name.contains(".."))throw new IllegalArgumentException("Invalid preset file");File file=new File(new File(getContext().getCacheDir(),"shared-presets"),name);if(!file.isFile())throw new IllegalArgumentException("Preset file does not exist");return file;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
