package com.atharvakale.facerecognition;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Local journal ensures failed or interrupted multi-file restore rolls back on startup. */
final class GoldenBackup {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final String JOURNAL = "GoldenBackupTransaction";
    static final String SNAPSHOT = "snapshot";
    static synchronized String snapshot(Context c) {
        JsonObject root=new JsonObject(); root.addProperty("format","GoldenFaceBackup"); root.addProperty("version",1);
        JsonObject prefs=new JsonObject(); root.add("preferences",prefs);
        for(String file:BackupCodec.FILES) {
            JsonObject entries=new JsonObject(); prefs.add(file,entries);
            for(Map.Entry<String,?> en:c.getSharedPreferences(file,Context.MODE_PRIVATE).getAll().entrySet()) {
                Object v=en.getValue(); JsonObject item=new JsonObject();
                if(v instanceof String) {item.addProperty("type","string"); item.addProperty("value",(String)v);}
                else if(v instanceof Boolean) {item.addProperty("type","boolean"); item.addProperty("value",(Boolean)v);}
                else if(v instanceof Integer) {item.addProperty("type","int"); item.addProperty("value",(Integer)v);}
                else if(v instanceof Long) {item.addProperty("type","long"); item.addProperty("value",(Long)v);}
                else if(v instanceof Float && !Float.isNaN((Float)v) && !Float.isInfinite((Float)v)) {item.addProperty("type","float"); item.addProperty("value",(Float)v);}
                else if(v instanceof Set) {item.addProperty("type","stringset"); JsonArray a=new JsonArray(); for(Object x:(Set<?>)v) {if(!(x instanceof String)) throw new IllegalStateException("Invalid set"); a.add((String)x);} item.add("value",a);}
                else throw new IllegalStateException("Unsupported stored value");
                entries.add(en.getKey(),item);
            }
        }
        return root.toString();
    }
    static String read(InputStream in) throws IOException {
        if(in==null) throw new IOException("No stream"); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n,total=0;
        while((n=in.read(b))!=-1) {total+=n; if(total>MAX_BYTES) throw new IOException("Backup too large"); out.write(b,0,n);}
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString(); }
        catch(CharacterCodingException e) { throw new IOException("Invalid UTF-8",e); }
    }
    static void write(Context c, JsonObject prefs) {
        for(String file:BackupCodec.FILES) {
            SharedPreferences.Editor ed=c.getSharedPreferences(file,Context.MODE_PRIVATE).edit().clear();
            for(Map.Entry<String,JsonElement> en:BackupCodec.obj(prefs.get(file)).entrySet()) {
                JsonObject w=BackupCodec.obj(en.getValue()); String t=w.get("type").getAsString(); JsonElement v=w.get("value"); String key=en.getKey();
                switch(t) {
                    case "string": ed.putString(key,v.getAsString()); break;
                    case "boolean": ed.putBoolean(key,v.getAsBoolean()); break;
                    case "int": ed.putInt(key,v.getAsInt()); break;
                    case "long": ed.putLong(key,v.getAsLong()); break;
                    case "float": ed.putFloat(key,v.getAsFloat()); break;
                    case "stringset": Set<String> set=new HashSet<>(); for(JsonElement x:v.getAsJsonArray()) set.add(x.getAsString()); ed.putStringSet(key,set); break;
                    default: throw new IllegalArgumentException("Unknown type");
                }
            }
            if(!ed.commit()) throw new IllegalStateException("Cannot write "+file);
        }
    }
    // Journal snapshots are our own raw current preferences, not external input: preserve
    // even a previously malformed face map exactly rather than silently discarding it.
    static void restoreSnapshot(Context c, String snapshot) throws IOException {
        JsonObject root=BackupCodec.obj(BackupCodec.parse(snapshot));
        write(c,BackupCodec.obj(root.get("preferences")));
    }
    private static File journal(Context c) { return new File(c.getFilesDir(), "golden-backup-rollback.json"); }
    private static File temporary(Context c) { return new File(c.getFilesDir(), "golden-backup-rollback.tmp"); }
    // The private journal is independent of SharedPreferences: commit(false) changes
    // its in-memory cache, and cannot safely be used as a transaction marker.
    private static void createJournal(Context c, String old) throws IOException {
        File target=journal(c), tmp=temporary(c);
        if(target.exists()) throw new IOException("Recovery journal already exists");
        byte[] bytes=old.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw new IOException("Current data too large for journal");
        try (FileOutputStream out=new FileOutputStream(tmp)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        if(!tmp.renameTo(target)) throw new IOException("Cannot install recovery journal");
    }
    private static void removeJournal(Context c) throws IOException {
        File f=journal(c);
        if(f.exists() && !f.delete()) throw new IOException("Cannot remove recovery journal");
    }
    static synchronized void recover(Context c) throws IOException {
        File f=journal(c);
        SharedPreferences legacy=c.getSharedPreferences(JOURNAL,Context.MODE_PRIVATE);
        String legacyOld=legacy.getString(SNAPSHOT,null);
        if(!f.exists() && legacyOld!=null) createJournal(c,legacyOld);
        if(f.exists()) {
            String old;
            try(FileInputStream in=new FileInputStream(f)) { old=read(in); }
            restoreSnapshot(c,old); // leave journal in place on *any* failure
            if(legacyOld!=null && !legacy.edit().remove(SNAPSHOT).commit())
                throw new IOException("Cannot clear legacy recovery journal");
            removeJournal(c); // last operation, never discard before rollback succeeds
        }
    }
    static final class RecoveryRequired extends IOException {
        RecoveryRequired(Throwable cause) { super("Recovery failed; restart required",cause); }
    }
    static synchronized void replace(Context c, JsonObject validated) throws IOException {
        try { recover(c); } catch(Exception failure) { throw new RecoveryRequired(failure); }
        String old=snapshot(c);
        createJournal(c,old); // no preferences changed until journal has been synced
        try {
            write(c,BackupCodec.obj(validated.get("preferences")));
            removeJournal(c); // commit point; no writes after it
        } catch(Exception failure) {
            try { recover(c); }
            catch(Exception rollback) { failure.addSuppressed(rollback); throw new RecoveryRequired(failure); }
            throw new IOException("Restore failed; prior data recovered",failure);
        }
    }
}
