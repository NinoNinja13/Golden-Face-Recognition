package com.atharvakale.facerecognition;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Loopback-only, in-memory status; no images or embeddings are served. */
final class LocalFaceServer implements AutoCloseable {
    static final int PORT = 8765;
    static final long EXPIRE_MS = 3000;
    private final ServerSocket server;
    private volatile boolean running = true;
    private String status = "starting", name = null, candidate = null;
    private int consecutive = 0;
    private long updatedAt = System.currentTimeMillis();
    private Thread thread;
    private volatile Socket client;

    LocalFaceServer(int port) throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
    }
    void start() {
        thread = new Thread(this::serve, "golden-local-http");
        thread.start();
    }
    synchronized void observed(String match) {
        if (match == null || match.trim().isEmpty()) { set("unknown", null); return; }
        if (match.equals(candidate)) consecutive++; else { candidate = match; consecutive = 1; }
        if (consecutive >= 3) { status = "recognized"; name = match; updatedAt = System.currentTimeMillis(); }
        else { status = "confirming"; name = null; updatedAt = System.currentTimeMillis(); }
    }
    synchronized void set(String newStatus, String newName) {
        status = newStatus; name = newName; candidate = null; consecutive = 0;
        updatedAt = System.currentTimeMillis();
    }
    synchronized String json() {
        String effective = status;
        String current = name;
        if (("recognized".equals(status) || "confirming".equals(status)) && System.currentTimeMillis() - updatedAt > EXPIRE_MS) {
            effective = "stale"; current = null;
        }
        return "{\"status\":" + quote(effective) + ",\"name\":" + (current == null ? "null" : quote(current)) + ",\"updated_at_ms\":" + updatedAt + "}";
    }
    private static String quote(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(String.format("\\u%04x", (int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private void serve() {
        while (running) {
            try (Socket socket = server.accept()) {
                client = socket;
                if (!running) continue;
                socket.setSoTimeout(2000);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line = in.readLine();
                if (line == null || line.length() > 2048) continue;
                String[] parts = line.split(" ");
                boolean ok = parts.length >= 2 && "GET".equals(parts[0]) && ("/".equals(parts[1]) || "/status".equals(parts[1]));
                byte[] body = (ok ? json() : "{\"error\":\"not_found\"}").getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                String headers = "HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found") + "\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
            } catch (IOException ignored) { /* includes shutdown and client disconnect */ }
            finally { client = null; }
        }
    }
    @Override public void close() {
        running = false;
        try { server.close(); } catch (IOException ignored) { }
        Socket connected = client;
        if (connected != null) try { connected.close(); } catch (IOException ignored) { }
        if (thread != null) thread.interrupt();
    }
}
