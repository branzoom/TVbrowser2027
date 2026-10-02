package com.tvbrowser.assistant;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String[][] DEFAULT_SITES = {
            {"哔哩哔哩", "https://www.bilibili.com"},
            {"腾讯视频", "https://v.qq.com"},
            {"爱奇艺", "https://www.iqiyi.com"},
            {"优酷", "https://www.youku.com"},
            {"芒果TV", "https://www.mgtv.com"},
            {"西瓜视频", "https://www.ixigua.com"},
            {"YouTube", "https://www.youtube.com"},
            {"百度", "https://www.baidu.com"},
    };

    /** 网页大小档位：数值是给内核的屏幕密度（×100），数值越大网页内容显示得越大。 */
    private static final int[] ZOOM_LEVELS = {250, 200, 175, 150, 125, 100};
    private static final String[] ZOOM_NAMES = {"超大", "特大", "大", "标准", "小", "特小"};
    private static final int ZOOM_DEFAULT = 150;

    /** 常见网站的品牌主题色（渐变起止色），按域名匹配。 */
    private static final Object[][] BRAND_COLORS = {
            {"bilibili.com", 0xFFFB7299, 0xFFE0457B},
            {"qq.com", 0xFFFF8A1F, 0xFFF25C05},
            {"iqiyi.com", 0xFF1BD026, 0xFF00A30B},
            {"youku.com", 0xFF2E90FF, 0xFF1462E0},
            {"mgtv.com", 0xFFFFC21A, 0xFFFF9500},
            {"ixigua.com", 0xFFFF5A6A, 0xFFE8243C},
            {"youtube.com", 0xFFE62117, 0xFFA8110B},
            {"baidu.com", 0xFF4E6EF2, 0xFF2932E1},
    };

    /** 每个进程只能创建一个 GeckoRuntime。 */
    private static GeckoRuntime sRuntime;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService iconLoader = Executors.newFixedThreadPool(3);
    private SharedPreferences prefs;
    private float density;

    private GeckoView geckoView;
    private GeckoSession session;
    private CursorView cursor;
    private ProgressBar progress;
    private ScrollView panel;
    private LinearLayout tileArea;
    private EditText urlInput;
    private TextView osd;

    private boolean desktopMode;
    private int zoom;
    private boolean pageLoaded;
    private boolean canGoBack;
    private boolean fullscreen;
    private String currentUrl;
    private String currentTitle;
    private long lastBackTime;
    private long mouseDownTime;
    private boolean resumed;

    private final Runnable hideCursor = () -> cursor.setVisibility(View.INVISIBLE);
    private final Runnable hideOsd = () -> osd.animate().alpha(0f).setDuration(300).start();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("browser", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        desktopMode = prefs.getBoolean("desktop", true);
        zoom = nearestZoom(prefs.getInt("zoom", ZOOM_DEFAULT));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        startRemoteServer();
        setupGecko();
        root.addView(geckoView, match());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP));

        cursor = new CursorView(this);
        root.addView(cursor, match());

        buildPanel();
        root.addView(panel, match());

        osd = new TextView(this);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        osd.setBackgroundColor(0xCC000000);
        osd.setPadding(dp(24), dp(12), dp(24), dp(12));
        osd.setAlpha(0f);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        olp.bottomMargin = dp(48);
        root.addView(osd, olp);

        // 切换设置后应用会自动重启，重启后回到之前的网页
        String resume = prefs.getString("resume_url", null);
        if (resume != null) {
            prefs.edit().remove("resume_url").apply();
            openUrl(resume);
        } else {
            showPanel();
        }
    }

    // ---------------------------------------------------------------- GeckoView

    private void setupGecko() {
        if (sRuntime == null) {
            GeckoRuntimeSettings.Builder b = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(false)
                    .aboutConfigEnabled(true)
                    // 电视内存只有 2~3GB：关掉“每个网站一个进程”，所有网页共用一个页面进程，
                    // 避免多个进程一起被系统低内存回收机制杀掉
                    .fissionEnabled(false)
                    .extensionsProcessEnabled(false)
                    .lowMemoryDetection(true)
                    .configFilePath(writeGeckoConfig());
            // 电视屏幕 1920 宽、系统密度 2.0，网页默认只有 960 宽；调低密度让电脑版网页排版更舒展
            if (desktopMode) b.displayDensityOverride(zoom / 100f);
            sRuntime = GeckoRuntime.create(getApplicationContext(), b.build());
        }
        geckoView = new GeckoView(this);
        createSession();
    }

    private final List<Long> recoverTimes = new ArrayList<>();

    /** 页面进程死掉后自动恢复；1 分钟内连续出事 3 次就不再自动重载，免得反复崩溃。 */
    private void autoRecover(String message) {
        long now = SystemClock.uptimeMillis();
        while (!recoverTimes.isEmpty() && now - recoverTimes.get(0) > 60_000) recoverTimes.remove(0);
        recoverTimes.add(now);
        if (recoverTimes.size() > 3) {
            rebuildSession(null);
            showPanel();
            showOsd("这个网页反复崩溃，已停止自动加载，可以换个网页或调小「网页大小」再试");
            return;
        }
        rebuildSession(currentUrl);
        showOsd(message);
    }

    /**
     * 强制重载：丢掉旧的网页会话（不管它是卡死、崩溃还是被杀），新建一个再打开网址。
     * 比普通刷新更彻底，页面进程已经没了也能恢复。
     */
    private void rebuildSession(String url) {
        GeckoSession old = session;
        geckoView.releaseSession();
        try {
            old.close();
        } catch (Exception ignored) {
        }
        fullscreen = false;
        canGoBack = false;
        createSession();
        if (url != null && url.startsWith("http")) {
            session.loadUri(url);
        } else {
            pageLoaded = false;
        }
    }

    private void forceReload() {
        String url = currentUrl;
        if (url == null || !url.startsWith("http")) {
            showOsd("还没有打开网页");
            return;
        }
        if (panel.getVisibility() == View.VISIBLE) hidePanel();
        rebuildSession(url);
        showOsd("正在重新加载…");
    }

    /**
     * Gecko 内核参数：限制成单个页面进程、不预启动备用进程、少缓存几个历史页面，
     * 尽量少占内存。
     */
    private String writeGeckoConfig() {
        File f = new File(getFilesDir(), "geckoview-config.yaml");
        String yaml = "prefs:\n"
                + "  fission.autostart: false\n"
                + "  dom.ipc.processCount: 1\n"
                + "  dom.ipc.processCount.webIsolated: 1\n"
                + "  dom.ipc.processPrelaunch.enabled: false\n"
                + "  dom.ipc.keepProcessesAlive.web: 0\n"
                + "  browser.sessionhistory.max_total_viewers: 0\n"
                + "  browser.cache.memory.capacity: 32768\n"
                + "  image.mem.surfacecache.max_size_kb: 65536\n";
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(yaml.getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
        return f.getAbsolutePath();
    }

    /** 创建网页会话；页面进程被杀或崩溃后也用它重建。 */
    private void createSession() {
        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(desktopMode ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                        : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktopMode ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                        : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .useTrackingProtection(false)
                .build());

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession s, boolean can) {
                canGoBack = can;
            }

            @Override
            public void onLocationChange(GeckoSession s, String url, List<ContentPermission> perms, Boolean userGesture) {
                currentUrl = url;
                if (url != null && url.startsWith("http")) prefs.edit().putString("last_url", url).apply();
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                String url = request.uri;
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")
                        || url.startsWith("data:") || url.startsWith("blob:")) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                // bilibili:// 之类的 App 跳转链接直接忽略，intent:// 有网页备用地址就打开它
                if (url.startsWith("intent://")) {
                    Matcher m = Pattern.compile("S\\.browser_fallback_url=([^;]+)").matcher(url);
                    if (m.find()) session.loadUri(Uri.decode(m.group(1)));
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                // 新窗口/新标签页的链接在当前页面打开
                session.loadUri(uri);
                return null;
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                pageLoaded = true;
            }

            @Override
            public void onProgressChange(GeckoSession s, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession s, String title) {
                currentTitle = title;
            }

            @Override
            public void onFullScreen(GeckoSession s, boolean full) {
                fullscreen = full;
                if (full) {
                    cursor.setVisibility(View.INVISIBLE);
                    showOsd("全屏播放：OK 暂停/播放 · ←→ 快退/快进 · 返回键退出全屏");
                } else {
                    showCursor();
                }
            }

            @Override
            public void onKill(GeckoSession s) {
                // 电视内存不足时，系统会杀掉页面进程；不处理的话画面会一直卡住
                if (s == session) handler.post(() -> autoRecover("电视内存不足，页面被系统回收，已自动重新加载"));
            }

            @Override
            public void onCrash(GeckoSession s) {
                if (s == session) handler.post(() -> autoRecover("页面崩溃了，已自动重新加载"));
            }
        });

        session.setPermissionDelegate(new PermissionDelegate() {
            @Override
            public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
                switch (perm.permission) {
                    case PERMISSION_AUTOPLAY_AUDIBLE:
                    case PERMISSION_AUTOPLAY_INAUDIBLE:
                    case PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                    case PERMISSION_PERSISTENT_STORAGE:
                    case PERMISSION_STORAGE_ACCESS:
                        return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                }
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        });

        session.open(sRuntime);
        geckoView.setSession(session);
    }

    /** 保存当前网页并重启应用，让内核设置（缩放、电脑/手机版）生效。 */
    private void restartApp() {
        if (currentUrl != null && currentUrl.startsWith("http")) {
            prefs.edit().putString("resume_url", currentUrl).commit();
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pi);
        finish();
        Process.killProcess(Process.myPid());
    }

    private void loadInput(String text) {
        loadInput(text, "baidu");
    }

    /**
     * 打开输入内容：包含网址就打开网址（支持 App 分享出来的「标题 + 链接」整段文字），
     * 否则用指定网站搜索。
     */
    private void loadInput(String text, String engine) {
        String url = toUrl(text, engine);
        if (url != null) openUrl(url);
    }

    private static String toUrl(String text, String engine) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return null;
        Matcher m = Pattern.compile("https?://[^\\s，。、“”\"'<>（）()]+").matcher(text);
        if (m.find()) return m.group();
        if (!text.contains(" ") && text.matches("^[^/\\s]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$")) {
            return "https://" + text;
        }
        String q;
        try {
            q = URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (engine == null) engine = "baidu";
        switch (engine) {
            case "bilibili": return "https://search.bilibili.com/all?keyword=" + q;
            case "qq": return "https://v.qq.com/x/search/?q=" + q;
            case "iqiyi": return "https://so.iqiyi.com/so/q_" + q;
            case "youku": return "https://so.youku.com/search_video/q_" + q;
            default: return "https://www.baidu.com/s?wd=" + q;
        }
    }

    // ---------------------------------------------------------------- 手机/电脑遥控

    private static RemoteServer sServer;
    private static volatile MainActivity sInstance;

    private ImageView remoteQr;
    private TextView remoteAddr;
    private String remoteUrl;

    private void startRemoteServer() {
        sInstance = this;
        if (sServer != null) return;
        sServer = new RemoteServer(this, new RemoteServer.Listener() {
            @Override
            public void onOpen(String text, String engine) {
                onUi(a -> a.remoteOpen(text, engine));
            }

            @Override
            public void onKey(String key) {
                onUi(a -> a.remoteKey(key));
            }

            @Override
            public void onBookmark(String name, String text) {
                onUi(a -> a.remoteBookmark(name, text));
            }

            @Override
            public JSONObject onState() {
                MainActivity a = sInstance;
                JSONObject o = new JSONObject();
                if (a == null) return o;
                try {
                    boolean home = a.panel.getVisibility() == View.VISIBLE;
                    o.put("home", home);
                    if (a.pageLoaded && !home) {
                        o.put("title", a.currentTitle == null ? "" : a.currentTitle);
                        o.put("url", a.currentUrl == null ? "" : a.currentUrl);
                    }
                } catch (Exception ignored) {
                }
                return o;
            }
        });
        sServer.start();
    }

    private interface UiAction { void run(MainActivity a); }

    private static void onUi(UiAction action) {
        MainActivity a = sInstance;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (!a.isFinishing()) action.run(a);
        });
    }

    /** 电视切到别的应用时，把浏览器调回前台。 */
    private void bringToFront() {
        if (!resumed) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        }
    }

    private void remoteOpen(String text, String engine) {
        String url = toUrl(text, engine);
        if (url == null) return;
        bringToFront();
        openUrl(url);
        showOsd("📱 手机发来：" + (url.length() > 60 ? url.substring(0, 60) + "…" : url));
    }

    private void remoteKey(String key) {
        if (key == null) return;
        long t = SystemClock.uptimeMillis();
        switch (key) {
            case "playpause":
                sendKeyToPage(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0), KeyEvent.KEYCODE_SPACE);
                sendKeyToPage(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0), KeyEvent.KEYCODE_SPACE);
                break;
            case "back":
                if (panel.getVisibility() == View.VISIBLE) {
                    if (pageLoaded) hidePanel();
                } else if (fullscreen) {
                    session.exitFullScreen();
                } else if (canGoBack) {
                    session.goBack();
                } else {
                    showPanel();
                }
                break;
            case "home":
                bringToFront();
                if (fullscreen) session.exitFullScreen();
                showPanel();
                break;
            case "reload":
                forceReload();
                break;
            case "pageup":
            case "pagedown":
                if (panel.getVisibility() == View.VISIBLE) break;
                sendMouse(MotionEvent.ACTION_SCROLL, 0, 0, key.equals("pageup") ? 8 : -8);
                break;
        }
    }

    private void remoteBookmark(String name, String text) {
        String url = toUrl(text, null);
        if (url == null || !url.startsWith("http")) return;
        if (name == null || name.trim().isEmpty()) name = Uri.parse(url).getHost();
        name = name.trim();
        if (name.length() > 8) name = name.substring(0, 8);
        List<String[]> sites = loadBookmarks();
        sites.add(new String[]{name, url});
        saveBookmarks(sites);
        rebuildTiles();
        showOsd("📱 已收藏：" + name);
    }

    /** 首页右上角的「手机扫码」卡片，按 OK 放大二维码。 */
    private View buildRemoteCard() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x1FFFFFFF);
        normal.setCornerRadius(dp(12));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setCornerRadius(dp(12));
        focused.setStroke(dp(3), 0xFFFF6A00);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);

        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(bg);
        card.setPadding(dp(8), dp(8), dp(16), dp(8));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> showBigQr());
        card.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start());

        remoteQr = new ImageView(this);
        remoteQr.setBackgroundColor(Color.WHITE);
        remoteQr.setPadding(dp(3), dp(3), dp(3), dp(3));
        card.addView(remoteQr, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText("用手机输入网址");
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t1);
        remoteAddr = new TextView(this);
        remoteAddr.setTextColor(0xFFB8C0D0);
        remoteAddr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(remoteAddr);
        card.addView(texts);
        return card;
    }

    /** 电视 IP 可能会变，每次打开首页时刷新地址和二维码。 */
    private void refreshRemoteInfo() {
        String ip = RemoteServer.localIp();
        int port = sServer == null ? -1 : sServer.getPort();
        String url = (ip == null || port < 0) ? null : "http://" + ip + ":" + port;
        if (url != null && url.equals(remoteUrl)) return;
        remoteUrl = url;
        if (url == null) {
            remoteAddr.setText("电视未连接网络");
            remoteQr.setImageDrawable(null);
        } else {
            remoteAddr.setText("扫码或浏览器打开 " + url);
            remoteQr.setImageBitmap(makeQr(url, dp(64)));
        }
    }

    private void showBigQr() {
        if (remoteUrl == null) {
            showOsd("电视未连接网络");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(32), dp(24), dp(32), dp(24));
        ImageView big = new ImageView(this);
        big.setBackgroundColor(Color.WHITE);
        big.setPadding(dp(10), dp(10), dp(10), dp(10));
        big.setImageBitmap(makeQr(remoteUrl, dp(240)));
        box.addView(big, new LinearLayout.LayoutParams(dp(240), dp(240)));
        TextView addr = new TextView(this);
        addr.setText(remoteUrl);
        addr.setTextColor(Color.WHITE);
        addr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        addr.setTypeface(Typeface.DEFAULT_BOLD);
        addr.setPadding(0, dp(16), 0, dp(4));
        box.addView(addr);
        TextView tip = new TextView(this);
        tip.setText("手机/电脑连接同一个 Wi‑Fi，扫码或在浏览器里输入上面的地址");
        tip.setTextColor(0xFFB8C0D0);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.addView(tip);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("用手机输入网址")
                .setView(box)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static Bitmap makeQr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private void openUrl(String url) {
        hidePanel();
        session.loadUri(url);
    }

    // ---------------------------------------------------------------- 按键处理

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // 首页面板：使用系统自带的焦点导航
        if (panel.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                // 忽略按住不放产生的重复按键，否则长按返回键打开首页后会立刻又关掉
                if (!down || e.getRepeatCount() > 0) return true;
                if (pageLoaded) {
                    hidePanel();
                } else if (code == KeyEvent.KEYCODE_BACK) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastBackTime < 2000) finish();
                    else showOsd("再按一次返回键退出");
                    lastBackTime = now;
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // 播放/暂停键：模拟键盘空格，视频网站普遍用空格控制播放
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            sendKeyToPage(e, KeyEvent.KEYCODE_SPACE);
            return true;
        }

        // 全屏视频：OK=空格(暂停/播放)，方向键直接交给网页（左右快退快进、上下音量）
        if (fullscreen) {
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    if (down && e.getRepeatCount() == 0) {
                        // 这次按下已用来退出全屏，别让抬起时再触发后退
                        backLongPressed = true;
                        session.exitFullScreen();
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (down) {
                        session.exitFullScreen();
                        showPanel();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    sendKeyToPage(e, KeyEvent.KEYCODE_SPACE);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    sendKeyToPage(e, code);
                    return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // 网页浏览：方向键控制鼠标
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveCursor(code, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    showCursor();
                    mouseDownTime = SystemClock.uptimeMillis();
                    sendMouse(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, 0, 0);
                    cursor.setPressed2(true);
                } else if (!down) {
                    sendMouse(MotionEvent.ACTION_UP, 0, 0, 0);
                    cursor.setPressed2(false);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                // 短按 = 后退；长按 = 打开首页（有些遥控器没有菜单键）
                if (down) {
                    if (e.getRepeatCount() == 0) backLongPressed = false;
                    else if (!backLongPressed && isLongPress(e)) {
                        backLongPressed = true;
                        showPanel();
                    }
                } else if (!backLongPressed) {
                    if (canGoBack) session.goBack();
                    else showPanel();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down) showPanel();
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) sendMouse(MotionEvent.ACTION_SCROLL, 0, 0, 8);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) sendMouse(MotionEvent.ACTION_SCROLL, 0, 0, -8);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean backLongPressed;

    private static boolean isLongPress(KeyEvent e) {
        return e.isLongPress() || e.getEventTime() - e.getDownTime() > 600;
    }

    private void sendKeyToPage(KeyEvent e, int asCode) {
        KeyEvent k = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), asCode,
                e.getRepeatCount(), 0, KeyEvent.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_KEYBOARD);
        if (k.getAction() == KeyEvent.ACTION_DOWN) geckoView.onKeyDown(asCode, k);
        else geckoView.onKeyUp(asCode, k);
    }

    private void moveCursor(int code, int repeat) {
        boolean wasHidden = cursor.getVisibility() != View.VISIBLE;
        showCursor();
        if (wasHidden && repeat == 0) return;

        float step = Math.min(dp(6) + repeat * dp(3), dp(40));
        float dx = 0, dy = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: dy = -step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = step; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -step; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = step; break;
        }
        float nx = cursor.getCursorX() + dx, ny = cursor.getCursorY() + dy;
        int w = cursor.getWidth(), h = cursor.getHeight(), edge = dp(4);
        cursor.setPosition(nx, ny);

        // 光标到达屏幕边缘后继续按：用鼠标滚轮滚动光标下方的区域
        float notches = 1 + Math.min(repeat, 6) / 2f;
        if (ny < edge && dy < 0) sendMouse(MotionEvent.ACTION_SCROLL, 0, 0, notches);
        else if (ny > h - edge && dy > 0) sendMouse(MotionEvent.ACTION_SCROLL, 0, 0, -notches);
        else if (nx < edge && dx < 0) sendMouse(MotionEvent.ACTION_SCROLL, 0, -notches, 0);
        else if (nx > w - edge && dx > 0) sendMouse(MotionEvent.ACTION_SCROLL, 0, notches, 0);
        else sendMouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
    }

    /** 向网页发送真实的鼠标事件（移动悬停 / 按下 / 抬起 / 滚轮）。 */
    private void sendMouse(int action, int buttons, float hScroll, float vScroll) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = cursor.getCursorX();
        pc.y = cursor.getCursorY();
        pc.pressure = action == MotionEvent.ACTION_DOWN ? 1f : 0f;
        pc.size = 1f;
        if (action == MotionEvent.ACTION_SCROLL) {
            pc.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
            pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        }
        boolean isButton = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP;
        MotionEvent ev = MotionEvent.obtain(isButton ? mouseDownTime : now, now, action, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (isButton) geckoView.onTouchEvent(ev);
        else geckoView.onGenericMotionEvent(ev);
        ev.recycle();
    }

    private void showCursor() {
        cursor.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, 8000);
    }

    // ---------------------------------------------------------------- 首页面板

    private void buildPanel() {
        panel = new ScrollView(this);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF7232838, 0xF70D0E12}));
        panel.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(20), dp(48), dp(20));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        panel.addView(col);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setClipChildren(false);
        col.addView(titleRow);

        TextView title = new TextView(this);
        title.setText("电视浏览器助手2027");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        titleRow.addView(buildRemoteCard());

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams urlRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        urlRowLp.topMargin = dp(10);
        col.addView(urlRow, urlRowLp);

        urlInput = new EditText(this);
        urlInput.setHint("输入网址或搜索内容，按 OK 键弹出键盘");
        urlInput.setHintTextColor(0xFF888888);
        urlInput.setTextColor(Color.BLACK);
        urlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        urlInput.setSingleLine(true);
        urlInput.setBackgroundResource(R.drawable.input_bg);
        urlInput.setPadding(dp(16), 0, dp(16), 0);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            loadInput(urlInput.getText().toString());
            return true;
        });
        urlRow.addView(urlInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        Button go = makeButton("打开", v -> loadInput(urlInput.getText().toString()));
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.MATCH_PARENT);
        goLp.leftMargin = dp(12);
        urlRow.addView(go, goLp);

        col.addView(sectionLabel("我的网站（长按 OK 键可删除）"));
        tileArea = new LinearLayout(this);
        tileArea.setOrientation(LinearLayout.VERTICAL);
        tileArea.setClipChildren(false);
        col.addView(tileArea);

        col.addView(sectionLabel("操作"));
        LinearLayout actions = new LinearLayout(this);
        actions.setClipChildren(false);
        col.addView(actions);
        actions.addView(makeButton("返回网页", v -> {
            if (pageLoaded) hidePanel(); else showOsd("还没有打开网页");
        }), actionLp());
        actions.addView(makeButton("收藏当前页", v -> addBookmark()), actionLp());
        actions.addView(makeButton("重新加载", v -> forceReload()), actionLp());
        Button uaButton = makeButton(desktopMode ? "电脑版网页 ✓" : "手机版网页 ✓", v -> {
            prefs.edit().putBoolean("desktop", !desktopMode).commit();
            restartApp();
        });
        actions.addView(uaButton, actionLp());
        Button zoomButton = makeButton(desktopMode ? "网页大小：" + zoomLabel(zoom) : "网页大小：自动",
                v -> showZoomPicker());
        zoomButton.setEnabled(desktopMode);
        actions.addView(zoomButton, actionLp());

        TextView help = new TextView(this);
        help.setText("遥控器用法：方向键移动鼠标 · OK 键点击（按住=长按） · 鼠标移到屏幕边缘继续按会滚动页面\n"
                + "返回键 = 后退 · 菜单键(≡) = 打开本页面 · 视频全屏后：OK 暂停/播放，←→ 快退/快进\n"
                + "网页卡住时：按菜单键 → 重新加载 · 切换电脑版/网页大小后应用会自动重启");
        help.setTextColor(0xFFAAAAAA);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        help.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(24);
        col.addView(help, hlp);

        Button about = makeButton("关于 · 开源许可", v -> showAbout());
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(180), dp(44));
        alp.topMargin = dp(12);
        col.addView(about, alp);

        rebuildTiles();
    }

    private void showAbout() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView t = new TextView(this);
        t.setText("电视浏览器助手2027 " + version + "\n"
                + "在电视大屏上浏览网页，手机扫码遥控。\n"
                + "开源地址：https://github.com/branzoom/TVbrowser2027\n\n"
                + "本应用使用了以下开源软件：\n\n"
                + "• Mozilla GeckoView（Firefox 浏览器内核）\n"
                + "  许可协议：Mozilla Public License 2.0\n"
                + "  源代码：https://github.com/mozilla-firefox/firefox\n"
                + "  协议全文：https://www.mozilla.org/MPL/2.0/\n\n"
                + "• ZXing（二维码生成）\n"
                + "  许可协议：Apache License 2.0\n"
                + "  源代码：https://github.com/zxing/zxing\n"
                + "  协议全文：https://www.apache.org/licenses/LICENSE-2.0\n\n"
                + "本应用未修改上述开源软件的源代码。\n"
                + "网页内容及视频版权归各网站所有，观看会员内容需使用您自己的会员账号。");
        t.setTextColor(0xFFE0E4EC);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(dp(28), dp(16), dp(28), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("关于")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 旧版本可能存下了不在档位里的数值，取最接近的档位。 */
    private static int nearestZoom(int z) {
        int best = ZOOM_DEFAULT;
        for (int l : ZOOM_LEVELS) if (Math.abs(l - z) < Math.abs(best - z)) best = l;
        return best;
    }

    private static String zoomLabel(int z) {
        for (int i = 0; i < ZOOM_LEVELS.length; i++) if (ZOOM_LEVELS[i] == z) return ZOOM_NAMES[i];
        return z + "%";
    }

    private void showZoomPicker() {
        String[] items = new String[ZOOM_LEVELS.length];
        int checked = -1;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            items[i] = ZOOM_NAMES[i] + (ZOOM_LEVELS[i] == ZOOM_DEFAULT ? "（推荐）" : "");
            if (ZOOM_LEVELS[i] == zoom) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("网页大小（越大字越大，一屏内容越少）")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    if (ZOOM_LEVELS[which] == zoom) return;
                    prefs.edit().putInt("zoom", ZOOM_LEVELS[which]).commit();
                    showOsd("正在应用新的网页大小…");
                    handler.postDelayed(this::restartApp, 300);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rebuildTiles() {
        tileArea.removeAllViews();
        List<String[]> sites = loadBookmarks();
        LinearLayout row = null;
        for (int i = 0; i < sites.size(); i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                tileArea.addView(row);
            }
            final String[] site = sites.get(i);
            final int index = i;
            View tile = makeSiteTile(site[0], site[1]);
            tile.setOnClickListener(v -> openUrl(site[1]));
            tile.setOnLongClickListener(v -> {
                confirmDelete(index, site[0]);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(100));
            lp.setMargins(0, dp(7), dp(16), dp(7));
            row.addView(tile, lp);
        }
    }

    /** 网站卡片：品牌主题色渐变背景 + 网站图标 + 名称。 */
    private View makeSiteTile(String name, String url) {
        String host = Uri.parse(url).getHost();
        if (host == null) host = url;
        int[] colors = brandColors(host);

        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        normal.setCornerRadius(dp(14));
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        focused.setCornerRadius(dp(14));
        focused.setStroke(dp(4), Color.WHITE);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(bg);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(120).start();
            v.setElevation(has ? dp(8) : 0);
        });

        // 白色圆角底座上放网站图标；图标没加载出来时显示名称首字
        FrameLayout badge = new FrameLayout(this);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.WHITE);
        badgeBg.setCornerRadius(dp(10));
        badge.setBackground(badgeBg);
        TextView letter = new TextView(this);
        letter.setText(name.isEmpty() ? "?" : name.substring(0, 1));
        letter.setTextColor(colors[1]);
        letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        letter.setGravity(Gravity.CENTER);
        badge.addView(letter, match());
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER);
        badge.addView(icon, ilp);
        tile.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));
        loadIcon(host, icon, letter);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(3), 0, dp(1), 0x55000000);
        label.setPadding(dp(8), dp(8), dp(8), 0);
        tile.addView(label);
        return tile;
    }

    private static int[] brandColors(String host) {
        for (Object[] b : BRAND_COLORS) {
            if (host.endsWith((String) b[0])) return new int[]{(Integer) b[1], (Integer) b[2]};
        }
        // 自己收藏的网站：按域名算出一个固定的颜色
        float hue = (host.hashCode() & 0x7fffffff) % 360;
        return new int[]{
                Color.HSVToColor(new float[]{hue, 0.55f, 0.80f}),
                Color.HSVToColor(new float[]{(hue + 20) % 360, 0.65f, 0.62f})};
    }

    /** 读取网站图标：先查本地缓存，没有就在后台下载 apple-touch-icon 或 favicon.ico。 */
    private void loadIcon(String host, ImageView into, View placeholder) {
        final File file = new File(new File(getCacheDir(), "icons"), host + ".png");
        if (file.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(file.getPath());
            if (bm != null) {
                into.setImageBitmap(bm);
                placeholder.setVisibility(View.GONE);
                return;
            }
        }
        iconLoader.execute(() -> {
            Bitmap bm = null;
            for (String path : new String[]{"/apple-touch-icon.png", "/favicon.ico"}) {
                bm = downloadBitmap("https://" + host + path);
                if (bm != null) break;
            }
            if (bm == null) return;
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
            }
            final Bitmap result = bm;
            handler.post(() -> {
                into.setImageBitmap(result);
                placeholder.setVisibility(View.GONE);
            });
        });
    }

    private static Bitmap downloadBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) return null;
            String type = c.getContentType();
            if (type != null && !type.startsWith("image")) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                byte[] data = buf.toByteArray();
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void confirmDelete(final int index, String name) {
        new AlertDialog.Builder(this)
                .setTitle("删除「" + name + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    List<String[]> sites = loadBookmarks();
                    if (index < sites.size()) sites.remove(index);
                    saveBookmarks(sites);
                    rebuildTiles();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addBookmark() {
        String url = currentUrl;
        if (!pageLoaded || url == null || !url.startsWith("http")) {
            showOsd("请先打开一个网页");
            return;
        }
        String title = currentTitle;
        if (TextUtils.isEmpty(title)) title = Uri.parse(url).getHost();
        if (title.length() > 8) title = title.substring(0, 8);
        List<String[]> sites = loadBookmarks();
        sites.add(new String[]{title, url});
        saveBookmarks(sites);
        rebuildTiles();
        showOsd("已收藏：" + title);
    }

    private List<String[]> loadBookmarks() {
        List<String[]> list = new ArrayList<>();
        String raw = prefs.getString("bookmarks", null);
        if (raw == null) {
            for (String[] s : DEFAULT_SITES) list.add(new String[]{s[0], s[1]});
            return list;
        }
        for (String line : raw.split("\n")) {
            int i = line.indexOf('\t');
            if (i > 0) list.add(new String[]{line.substring(0, i), line.substring(i + 1)});
        }
        return list;
    }

    private void saveBookmarks(List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : list) sb.append(s[0].replace('\t', ' ').replace('\n', ' ')).append('\t').append(s[1]).append('\n');
        prefs.edit().putString("bookmarks", sb.toString()).apply();
    }

    private void showPanel() {
        panel.setVisibility(View.VISIBLE);
        refreshRemoteInfo();
        cursor.setVisibility(View.INVISIBLE);
        if (currentUrl != null && currentUrl.startsWith("http")) urlInput.setText(currentUrl);
        panel.post(() -> {
            View first = tileArea.getChildCount() > 0 ? ((ViewGroup) tileArea.getChildAt(0)).getChildAt(0) : urlInput;
            if (first != null) first.requestFocus();
        });
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
        geckoView.requestFocus();
        showCursor();
    }

    // ---------------------------------------------------------------- 工具方法

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setBackgroundResource(R.drawable.tile_bg);
        b.setStateListAnimator(null);
        b.setFocusable(true);
        b.setOnClickListener(l);
        b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start());
        return b;
    }

    private LinearLayout.LayoutParams actionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(64));
        lp.setMargins(0, dp(8), dp(16), dp(8));
        return lp;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFCCCCCC);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private void showOsd(String text) {
        osd.setText(text);
        osd.animate().cancel();
        osd.setAlpha(1f);
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 2500);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        session.setActive(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        session.setActive(true);
    }

    @Override
    protected void onDestroy() {
        if (sInstance == this) sInstance = null;
        handler.removeCallbacksAndMessages(null);
        session.close();
        super.onDestroy();
    }
}
