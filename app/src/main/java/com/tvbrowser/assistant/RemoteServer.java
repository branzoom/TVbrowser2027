package com.tvbrowser.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 局域网里的小网页服务：手机/电脑打开 http://电视IP:端口 就能给电视发送网址、搜索和遥控指令。
 */
public class RemoteServer {

    public interface Listener {
        /** 打开网址或搜索；engine 为搜索引擎代号。 */
        void onOpen(String text, String engine);
        void onKey(String key);
        void onBookmark(String name, String text);
        /** 返回电视当前状态（在后台线程调用，实现方需自行保证线程安全）。 */
        JSONObject onState();
    }

    private static final int[] PORTS = {8765, 8766, 8767, 18765};

    private final Context context;
    private final Listener listener;
    private ServerSocket serverSocket;
    private int port = -1;
    private String html;

    public RemoteServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public int getPort() { return port; }

    public void start() {
        for (int p : PORTS) {
            try {
                serverSocket = new ServerSocket(p);
                port = p;
                break;
            } catch (IOException ignored) {
            }
        }
        if (serverSocket == null) return;
        Thread t = new Thread(this::acceptLoop, "RemoteServer");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                final Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s), "RemoteServer-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(i + 1).trim());
                }
            }
            byte[] body = new byte[Math.min(contentLength, 64 * 1024)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            Map<String, String> form = parseForm(new String(body, 0, read, StandardCharsets.UTF_8));

            OutputStream out = socket.getOutputStream();
            if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
                send(out, 200, "text/html; charset=utf-8", page());
            } else if (method.equals("GET") && path.equals("/api/state")) {
                JSONObject st = listener.onState();
                send(out, 200, "application/json; charset=utf-8", st == null ? "{}" : st.toString());
            } else if (method.equals("POST") && path.equals("/api/open")) {
                String text = form.get("text");
                if (text == null || text.trim().isEmpty()) {
                    send(out, 400, "application/json", "{\"ok\":false,\"msg\":\"内容为空\"}");
                } else {
                    listener.onOpen(text, form.get("engine"));
                    send(out, 200, "application/json", "{\"ok\":true}");
                }
            } else if (method.equals("POST") && path.equals("/api/key")) {
                listener.onKey(form.get("k"));
                send(out, 200, "application/json", "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/bookmark")) {
                String text = form.get("text");
                if (text == null || text.trim().isEmpty()) {
                    send(out, 400, "application/json", "{\"ok\":false,\"msg\":\"网址为空\"}");
                } else {
                    listener.onBookmark(form.get("name"), text);
                    send(out, 200, "application/json", "{\"ok\":true}");
                }
            } else {
                send(out, 404, "text/plain; charset=utf-8", "Not Found");
            }
        } catch (Exception ignored) {
        }
    }

    private String page() throws IOException {
        if (html == null) {
            try (InputStream in = context.getAssets().open("remote.html")) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                html = buf.toString("UTF-8");
            }
        }
        return html;
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) break;
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> map = new HashMap<>();
        if (s == null || s.isEmpty()) return map;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            try {
                if (i > 0) map.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    /** 电视在局域网里的 IPv4 地址（Wi‑Fi 或有线都可以）。 */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
