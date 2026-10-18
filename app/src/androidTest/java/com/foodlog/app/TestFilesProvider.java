package com.foodlog.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;

// Only in instrumentation APK; Java avoids a dependency on the target APK classloader.
public class TestFilesProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { return null; }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { return 0; }
    @Override public int delete(Uri uri,String selection,String[] args) { return 0; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        String name;
        if ("legacy".equals(uri.getLastPathSegment())) name="legacy_personal.json";
        else if ("backup".equals(uri.getLastPathSegment())) name="backup.json";
        else throw new FileNotFoundException("Unsupported test URI");
        File file=new File(getContext().getCacheDir(),name);
        if (name.equals("legacy_personal.json")) {
            try (InputStream input=getContext().getAssets().open(name); FileOutputStream output=new FileOutputStream(file)) {
                byte[] buffer=new byte[8192];int read;
                while ((read=input.read(buffer))!=-1) output.write(buffer,0,read);
            } catch(IOException ex) { throw new FileNotFoundException(ex.toString()); }
        }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
    }
}
