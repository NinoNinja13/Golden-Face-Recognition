package com.atharvakale.facerecognition;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** Strict, versioned, Android-independent backup validation. No partial repair. */
final class BackupCodec {
    static final String[] FILES = {"HashMap", "Distance", "GoldenOptions", "GoldenBackgroundCamera", "GoldenScreenPolicy", "GoldenBoot"};
    static JsonElement readValue(JsonReader r, int depth) throws IOException {
        if (depth > 30) throw new IOException("Nesting too deep");
        switch (r.peek()) {
            case BEGIN_OBJECT: {
                JsonObject o = new JsonObject(); r.beginObject();
                while (r.hasNext()) { String k = r.nextName(); if (o.has(k)) throw new IOException("Duplicate key"); o.add(k,readValue(r,depth+1)); }
                r.endObject(); return o;
            }
            case BEGIN_ARRAY: { JsonArray a = new JsonArray(); r.beginArray(); while(r.hasNext()) a.add(readValue(r,depth+1)); r.endArray(); return a; }
            case STRING: return new JsonPrimitive(r.nextString());
            case NUMBER: try { return new JsonPrimitive(new BigDecimal(r.nextString())); } catch (NumberFormatException e) { throw new IOException(e); }
            case BOOLEAN: return new JsonPrimitive(r.nextBoolean());
            case NULL: r.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Invalid JSON");
        }
    }
    static JsonElement parse(String json) throws IOException {
        JsonReader r = new JsonReader(new StringReader(json)); r.setLenient(false);
        JsonElement e = readValue(r,0); if(r.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing data"); return e;
    }
    static JsonObject obj(JsonElement e) { if(e==null || !e.isJsonObject()) throw new IllegalArgumentException("Object required"); return e.getAsJsonObject(); }
    static boolean isString(JsonElement e) { return e!=null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(); }
    static void keys(JsonObject o, String... keys) { Set<String> allowed = new HashSet<>(Arrays.asList(keys)); if(o.size()!=allowed.size() || !allowed.containsAll(o.keySet())) throw new IllegalArgumentException("Unexpected or missing field"); }
    static JsonObject validate(String text) throws IOException {
        JsonObject root=obj(parse(text)); keys(root,"format","version","preferences");
        if(!isString(root.get("format")) || !"GoldenFaceBackup".equals(root.get("format").getAsString())) throw new IllegalArgumentException("Format");
        JsonElement version=root.get("version"); if(!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || !"1".equals(version.getAsString())) throw new IllegalArgumentException("Version");
        JsonObject prefs=obj(root.get("preferences")); keys(prefs,FILES);
        for(String file:FILES) {
            JsonObject entries=obj(prefs.get(file));
            for(Map.Entry<String,JsonElement> en:entries.entrySet()) {
                if(en.getKey().isEmpty() || en.getKey().length()>1024) throw new IllegalArgumentException("Preference key");
                JsonObject wrapped=obj(en.getValue()); keys(wrapped,"type","value");
                if(!isString(wrapped.get("type"))) throw new IllegalArgumentException("Type");
                String type=wrapped.get("type").getAsString(); JsonElement value=wrapped.get("value");
                if("string".equals(type)) { if(!isString(value)) throw new IllegalArgumentException("String"); }
                else if("boolean".equals(type)) { if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Boolean"); }
                else if("int".equals(type) || "long".equals(type)) { if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Integer"); try { long n=new BigDecimal(value.getAsString()).longValueExact(); if("int".equals(type) && (n<Integer.MIN_VALUE || n>Integer.MAX_VALUE)) throw new ArithmeticException(); } catch(ArithmeticException ex) { throw new IllegalArgumentException("Integer",ex); } }
                else if("float".equals(type)) { if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || (Float.isNaN(value.getAsFloat()) || Float.isInfinite(value.getAsFloat()))) throw new IllegalArgumentException("Float"); }
                else if("stringset".equals(type)) { if(!value.isJsonArray()) throw new IllegalArgumentException("Set"); Set<String> seen=new HashSet<>(); for(JsonElement x:value.getAsJsonArray()) if(!isString(x) || !seen.add(x.getAsString())) throw new IllegalArgumentException("Set entry"); }
                else throw new IllegalArgumentException("Unsupported type");
                String key=en.getKey();
                if(file.equals("HashMap") && (!key.equals("map") || !type.equals("string"))) throw new IllegalArgumentException("HashMap schema");
                if(file.equals("Distance") && key.equals("distance") && !type.equals("float")) throw new IllegalArgumentException("Distance type");
                if(file.equals("GoldenBackgroundCamera") && key.equals("lens") && (!type.equals("int") || (value.getAsInt()!=0 && value.getAsInt()!=1))) throw new IllegalArgumentException("Lens");
                if(file.equals("GoldenOptions") && key.equals("language") && (!type.equals("string") || !(value.getAsString().equals("en") || value.getAsString().equals("pt-BR")))) throw new IllegalArgumentException("Language");
                if(((file.equals("GoldenOptions") && (key.equals("overlay") || key.equals("macrodroid"))) || (file.equals("GoldenScreenPolicy") && key.equals("recognize_screen_off")) || (file.equals("GoldenBoot") && key.equals("resume"))) && !type.equals("boolean")) throw new IllegalArgumentException("Boolean setting");
            }
        }
        JsonObject faces=obj(prefs.get("HashMap"));
        if(faces.has("map")) validateFaces(obj(faces.get("map")).get("value").getAsString());
        return root;
    }
    static void validateFaces(String json) throws IOException {
        JsonObject map=obj(parse(json));
        for(Map.Entry<String,JsonElement> en:map.entrySet()) {
            if(en.getKey().trim().isEmpty() || en.getKey().length()>4096) throw new IllegalArgumentException("Face name");
            JsonObject face=obj(en.getValue());
            for (String field : face.keySet()) if (!Arrays.asList("id", "title", "distance", "extra").contains(field)) throw new IllegalArgumentException("Recognition field");
            for (String field : Arrays.asList("id", "title")) { JsonElement v=face.get(field); if(v!=null && !v.isJsonNull() && !isString(v)) throw new IllegalArgumentException("Recognition text"); }
            JsonElement distance=face.get("distance"); if(distance!=null && !distance.isJsonNull() && (!distance.isJsonPrimitive() || !distance.getAsJsonPrimitive().isNumber() || Float.isNaN(distance.getAsFloat()) || Float.isInfinite(distance.getAsFloat()))) throw new IllegalArgumentException("Recognition distance");
            JsonElement extra=face.get("extra");
            if(extra==null || !extra.isJsonArray() || extra.getAsJsonArray().size()==0) throw new IllegalArgumentException("Samples");
            for(JsonElement row:extra.getAsJsonArray()) {
                if(!row.isJsonArray() || row.getAsJsonArray().size()!=192) throw new IllegalArgumentException("Vector length");
                for(JsonElement n:row.getAsJsonArray()) if(!n.isJsonPrimitive() || !n.getAsJsonPrimitive().isNumber() || (Float.isNaN(n.getAsFloat()) || Float.isInfinite(n.getAsFloat()))) throw new IllegalArgumentException("Vector value");
            }
        }
    }
}
