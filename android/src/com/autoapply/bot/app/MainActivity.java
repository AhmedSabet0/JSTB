package com.autoapply.bot.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.autoapply.bot.R;
import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app's screen: the dashboard, the LinkedIn sign-in screen, Live view, and the in-app browser for applying on a
 *  company's site (it fills the form for you to check and send). Jobs shared to the app from LinkedIn or a browser
 *  join your list. */
public final class MainActivity extends Activity {
    static final String ASSET_HOST = "appassets.androidplatform.net";
    static final int REQ_FILE = 1;
    static final int REQ_NOTIFY = 2;
    static final int REQ_SITE_FILE = 3;

    private AndroidPlatform platform;
    private FrameLayout root;
    private WebView dash;
    private LinearLayout signInPane;
    private WebView signInWeb;
    private LinearLayout livePane;
    private ValueCallback<Uri[]> fileCallback;
    private boolean dark;
    private volatile boolean destroyed;
    private final ExecutorService bridgePool = Executors.newCachedThreadPool();
    private boolean askedNotify;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        platform = AndroidPlatform.get(this);
        platform.activity = new WeakReference<MainActivity>(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        root = new FrameLayout(this);
        dash = new WebView(this);
        WebSettings s = dash.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        dash.addJavascriptInterface(new Bridge(), "AA");
        dash.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!ASSET_HOST.equals(u.getHost())) {
                    return null;
                }
                return asset(u.getPath());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (ASSET_HOST.equals(u.getHost())) {
                    return false;
                }
                openOutside(u);  // job links, openrouter.ai/keys...: in the phone's browser
                return true;
            }
        });
        dash.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/plain"});
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        dash.setBackgroundColor(dark ? 0xff0b1017 : 0xfff3f5f8);
        root.addView(dash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fitBars();
        setContentView(root);
        styleBars();  // after setContentView: the bars' controller only exists once the window has its views
        if (Build.VERSION.SDK_INT >= 33) {
            Back33.register(this);
        }
        dash.loadUrl("https://" + ASSET_HOST + "/ui/index.html");
        handle(getIntent());
    }

    private int barColor() {
        return dark ? 0xff121923 : 0xffffffff;
    }

    @SuppressWarnings("deprecation")
    private void styleBars() {
        Window w = getWindow();
        w.getDecorView();  // makes sure the window's views exist (getInsetsController needs them)
        if (Build.VERSION.SDK_INT >= 30) {
            // Edge to edge (Android 15+ always draws apps under the bars): the app pads itself in fitBars().
            w.setDecorFitsSystemWindows(false);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(false);
            }
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
            return;
        }
        int bar = barColor();
        w.setStatusBarColor(bar);
        w.setNavigationBarColor(bar);
        if (!dark) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** Keep the screens clear of the status bar, the navigation bar, a notch and the keyboard. */
    private void fitBars() {
        if (Build.VERSION.SDK_INT < 30) {
            return;  // older Android fits the window between the bars by itself
        }
        root.setBackgroundColor(barColor());
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            }
        });
    }

    private WebResourceResponse asset(String path) {
        String p = path == null ? "" : path;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("..")) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<String, String>(), null);
        }
        String mime = p.endsWith(".html") ? "text/html" : p.endsWith(".css") ? "text/css"
                : (p.endsWith(".js") || p.endsWith(".mjs")) ? "text/javascript" : p.endsWith(".svg") ? "image/svg+xml"
                : p.endsWith(".woff2") ? "font/woff2" : p.endsWith(".txt") ? "text/plain" : "application/octet-stream";
        try {
            InputStream in = getAssets().open(p);
            HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<String, String>(), null);
        }
    }

    private void openOutside(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    /** What the dashboard can call: window.AA.call(id, path, bodyJson) (answers via window.__aaDone), or AA.api. */
    final class Bridge {
        @JavascriptInterface
        public String api(String path, String body) {
            return platform.api.handle(path, body == null || body.isEmpty() || "undefined".equals(body) ? null : body);
        }

        @JavascriptInterface
        public void call(final String id, final String path, final String body) {
            bridgePool.execute(new Runnable() {
                @Override
                public void run() {
                    String out = api(path, body);
                    final String js = "window.__aaDone && window.__aaDone(" + Json.stringify(id) + "," + Json.stringify(out) + ")";
                    platform.main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) {
                                dash.evaluateJavascript(js, null);
                            }
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void liveView() {
            platform.main.post(new Runnable() {
                @Override
                public void run() {
                    showLiveView();
                }
            });
        }

        /** Copy an answer, to paste it into a form in another app (LinkedIn). */
        @JavascriptInterface
        public void copy(final String text) {
            platform.main.post(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Auto Apply", text == null ? "" : text));
                    }
                }
            });
        }

        /** A job shared to the app while the dashboard was loading: {ok, item} or {ok: false, error}. */
        @JavascriptInterface
        public String pending() {
            String p = pendingShare;
            pendingShare = "";
            return p == null ? "" : p;
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ------------------------------------------------------------------ intents
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private volatile String pendingShare = "";

    private void handle(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null
                && "autoapply".equals(intent.getData().getScheme())) {
            final Uri back = intent.getData();
            intent.setAction(null);  // back from Google's page: onResume tells the screens
            closePanes();
            final String code = back.getQueryParameter("code");
            if ("auth".equals(back.getHost()) && code != null && !code.isEmpty()) {   // Sign in with Google
                bridgePool.execute(new Runnable() {
                    @Override
                    public void run() {
                        String r = platform.api.handle("/api/account/google-finish", Json.stringify(Json.obj("code", code)));
                        js("window.aaGoogle && window.aaGoogle(" + Json.stringify(r) + ")");
                    }
                });
            }
            return;
        }
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            intent.setAction(null);  // once
            closePanes();
            if (text == null && subject == null) {
                return;
            }
            bridgePool.execute(new Runnable() {
                @Override
                public void run() {
                    pendingShare = platform.api.handle("/api/copilot/add",
                            Json.stringify(Json.obj("text", text == null ? "" : text, "subject", subject == null ? "" : subject)));
                    js("window.aaPending && window.aaPending()");
                }
            });
            return;
        }
        String open = intent == null ? null : intent.getStringExtra("open");
        if ("signin".equals(open)) {
            showSignIn();
        } else if ("ready".equals(open)) {   // the notification of applications ready to send
            closePanes();
            dash.evaluateJavascript("window.aaShowReady && window.aaShowReady()", null);
        } else if ("question".equals(open) || "brief".equals(open)) {
            closePanes();
            dash.evaluateJavascript("window.aaShow && window.aaShow('dashboard')", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        platform.activityResumed = true;
        Notifs.cancel(this, Notifs.ID_BRIEF);
        js("window.aaResumed && window.aaResumed()");  // back from LinkedIn: did you apply?
        new Thread(new Runnable() {
            @Override
            public void run() {
                platform.api.handle("/api/here", "{}");
            }
        }).start();
    }

    @Override
    protected void onPause() {
        platform.activityResumed = false;
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        bridgePool.shutdown();
        closePanes();
        if (platform.activity() == this) {
            platform.activity = new WeakReference<MainActivity>(null);
        }
        dash.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {  // Android 12 and older; newer ones call Back33
        handleBack();
    }

    void handleBack() {
        if (browserPane != null) {
            if (browserWeb != null && browserWeb.canGoBack()) {
                browserWeb.goBack();
            } else {
                closeBrowser();
            }
            return;
        }
        if (signInPane != null || livePane != null) {
            closePanes();
            return;
        }
        // Back goes to the previous screen of the app; on the home screen the app goes to the background
        // (the bot keeps working).
        dash.evaluateJavascript("window.aaBack ? !!window.aaBack() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if (!"true".equals(handled) && !destroyed) {
                    moveTaskToBack(true);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
        if (request == REQ_SITE_FILE && browserFiles != null) {
            browserFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            browserFiles = null;
        }
    }

    // ------------------------------------------------------------------ panes
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout pane(String title, String buttonText, View.OnClickListener onButton, String note) {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundColor(dark ? 0xff0b1017 : 0xffffffff);
        pane.setClickable(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(10), dp(10));
        bar.setBackgroundColor(dark ? 0xff121923 : 0xffffffff);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(dark ? 0xffe5ebf2 : 0xff0f172a);
        texts.addView(t);
        if (note != null) {
            TextView n = new TextView(this);
            n.setText(note);
            n.setTextSize(12);
            n.setTextColor(dark ? 0xff8b98a9 : 0xff64748b);
            texts.addView(n);
        }
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(this);
        b.setText(buttonText);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xff0a66c2);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setOnClickListener(onButton);
        bar.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        pane.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(dark ? 0xff222c39 : 0xffe2e8f0);
        pane.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return pane;
    }

    void closePanes() {
        if (browserPane != null) {
            root.removeView(browserPane);
            browserPane = null;
            if (browserWeb != null) {
                browserWeb.destroy();
                browserWeb = null;
            }
            if (browserFiles != null) {
                browserFiles.onReceiveValue(null);
                browserFiles = null;
            }
        }
        if (signInPane != null) {
            root.removeView(signInPane);
            signInPane = null;
            if (signInWeb != null) {
                signInWeb.destroy();
                signInWeb = null;
            }
        }
        if (livePane != null) {
            WebViewPage p = platform.page;
            if (p != null) {
                p.hide();
            }
            root.removeView(livePane);
            livePane = null;
        }
    }

    /** LinkedIn's sign-in page. The bot's hidden page shares the same session (cookies). */
    void showSignIn() {
        closePanes();
        signInPane = pane("Sign in to LinkedIn", "Done", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signInFinished(false);
            }
        }, "Use your email and password (\"Sign in with Google\" does not work inside apps).");
        signInWeb = new WebView(this);
        WebViewPage.configure(signInWeb);
        WebSettings s = signInWeb.getSettings();
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        signInWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"https".equals(scheme) && !"http".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Uri u = Uri.parse(url);
                String path = u.getPath() == null ? "" : u.getPath();
                if (WebViewPage.isLinkedIn(u) && (path.startsWith("/feed") || path.startsWith("/jobs") || path.startsWith("/mynetwork"))
                        && platform.signedIn()) {
                    signInFinished(true);
                }
            }
        });
        signInPane.addView(signInWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(signInPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        signInWeb.loadUrl("https://www.linkedin.com/login");
    }

    private void signInFinished(boolean auto) {
        CookieManager.getInstance().flush();
        boolean ok = platform.signedIn();
        if (auto || ok) {
            Toast.makeText(this, ok ? "Signed in to LinkedIn." : "Not signed in yet.", Toast.LENGTH_SHORT).show();
        }
        closePanes();
        new Thread(new Runnable() {
            @Override
            public void run() {
                platform.api.handle("/api/signed-in", "{}");
            }
        }).start();
        dash.evaluateJavascript("window.aaRefresh && window.aaRefresh()", null);
    }

    /** Watch the bot's own LinkedIn page while it works (scaled down; the page keeps its desktop size). */
    void showLiveView() {
        final WebViewPage p = platform.page;
        if (p == null || p.isClosed()) {
            Toast.makeText(this, "Start the bot first. Live view shows its LinkedIn page while it works.", Toast.LENGTH_LONG).show();
            return;
        }
        closePanes();
        livePane = pane("Live view", "Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanes();
            }
        }, "The bot's LinkedIn page, shrunk to fit. It keeps working while you watch.");
        final FrameLayout box = new FrameLayout(this);
        box.setClipChildren(true);
        box.setBackgroundColor(Color.WHITE);
        livePane.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(livePane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.post(new Runnable() {
            @Override
            public void run() {
                if (livePane != null) {
                    p.showIn(box, MainActivity.this);
                }
            }
        });
    }

    // ------------------------------------------------------------------ the in-app browser (applying on a company's site)
    private LinearLayout browserPane;
    private WebView browserWeb;
    private ProgressBar browserProgress;
    private TextView browserTitleView;
    private TextView browserHostView;
    private Button browserFillBtn;
    private Button browserMainBtn;
    private ValueCallback<Uri[]> browserFiles;
    private String browserJob = "";
    private String browserJobTitle = "";
    private boolean browserApplied;
    private String fitAsked = "";
    private String fillJs;

    void js(final String code) {
        platform.main.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) {
                    dash.evaluateJavascript(code, null);
                }
            }
        });
    }

    private String fillScript() {
        if (fillJs == null) {
            try {
                InputStream in = getAssets().open("fill.js");
                try {
                    fillJs = new String(Util.readAll(in), Util.UTF8);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                fillJs = "";
            }
        }
        return fillJs;
    }

    /** evaluateJavascript gives the script's value as JSON; our scripts return JSON text, so it is a string in JSON. */
    private static String jsText(String raw) {
        Object v = Json.tryParse(raw);
        return v instanceof String ? (String) v : "";
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        return g;
    }

    private Button barButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setTextColor(primary ? Color.WHITE : (dark ? 0xffe7edf6 : 0xff13213c));
        b.setBackground(primary ? round(dark ? 0xff3d8fe0 : 0xff0a66c2, 0) : round(dark ? 0xff152034 : 0xffffffff, dark ? 0xff2a3a55 : 0xffd5dde8));
        return b;
    }

    /** A company's job page in the app: Fill puts your answers in its form (you check them and send it yourself), and
     *  the file button offers your CV. `jobId` is the job in your list; without it, Save adds the page to your list. */
    void showBrowser(String url, String jobId, String title) {
        closePanes();
        browserJob = jobId == null ? "" : jobId;
        browserJobTitle = title == null ? "" : title;
        browserApplied = false;
        fitAsked = "";
        int bg = dark ? 0xff0d1420 : 0xffffffff;
        int ink = dark ? 0xffe7edf6 : 0xff13213c;
        int ink2 = dark ? 0xff9aa9c0 : 0xff55637d;
        browserPane = new LinearLayout(this);
        browserPane.setOrientation(LinearLayout.VERTICAL);
        browserPane.setBackgroundColor(bg);
        browserPane.setClickable(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(6), dp(14), dp(6));
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(ink);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeBrowser();
            }
        });
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        browserTitleView = new TextView(this);
        browserTitleView.setTextSize(15.5f);
        browserTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        browserTitleView.setTextColor(ink);
        browserTitleView.setSingleLine(true);
        browserTitleView.setEllipsize(TextUtils.TruncateAt.END);
        browserTitleView.setText(browserJobTitle.isEmpty() ? "Loading…" : browserJobTitle);
        browserHostView = new TextView(this);
        browserHostView.setTextSize(12.5f);
        browserHostView.setTextColor(ink2);
        browserHostView.setSingleLine(true);
        browserHostView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(browserTitleView);
        texts.addView(browserHostView);
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        browserPane.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        browserProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        browserProgress.setMax(100);
        browserProgress.setIndeterminate(false);
        browserPane.addView(browserProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        browserWeb = new WebView(this);
        WebSettings s = browserWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browserWeb, true);
        browserWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    return false;
                }
                if ("mailto".equals(scheme) || "tel".equals(scheme)) {
                    openOutside(u);
                }
                return true;  // other apps' links (intent:, market:...) stay closed
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                browserProgress.setVisibility(View.INVISIBLE);
                showBrowserPage(view);
                readJobPage(view, url);
            }
        });
        browserWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                browserProgress.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
                browserProgress.setProgress(p);
            }

            @Override
            public void onReceivedTitle(WebView view, String t) {
                showBrowserPage(view);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (browserFiles != null) {
                    browserFiles.onReceiveValue(null);
                }
                browserFiles = callback;
                chooseSiteFile(params);
                return true;
            }
        });
        browserPane.addView(browserWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View line = new View(this);
        line.setBackgroundColor(dark ? 0xff22304a : 0xffe2e8f0);
        browserPane.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10));
        bottom.setBackgroundColor(dark ? 0xff111a2b : 0xfff6f8fb);
        browserFillBtn = barButton("Fill the form", false);
        browserFillBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fillForm();
            }
        });
        browserMainBtn = barButton("", true);
        browserMainBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (browserJob.isEmpty()) {
                    saveBrowserJob();
                } else {
                    markBrowserApplied();
                }
            }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(50), 1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(50), 1);
        half2.leftMargin = dp(10);
        bottom.addView(browserFillBtn, half);
        bottom.addView(browserMainBtn, half2);
        browserPane.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paintBrowserButtons();
        root.addView(browserPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        browserWeb.loadUrl(url);
    }

    private void paintBrowserButtons() {
        if (browserMainBtn != null) {
            browserMainBtn.setText(browserJob.isEmpty() ? "Save to my jobs" : "I applied");
        }
    }

    private void showBrowserPage(WebView view) {
        if (browserWeb != view || browserTitleView == null) {
            return;
        }
        String t = view.getTitle();
        String url = view.getUrl() == null ? "" : view.getUrl();
        Uri u = Uri.parse(url);
        browserTitleView.setText(t != null && !t.trim().isEmpty() && !url.contains(t) ? t.trim()
                : (browserJobTitle.isEmpty() ? (u.getHost() == null ? "" : u.getHost()) : browserJobTitle));
        browserHostView.setText((u.getHost() == null ? "" : u.getHost().replaceFirst("^www\\.", "")) + (u.getPath() == null ? "" : u.getPath()));
    }

    /** The job's page: its text tells how well the job fits your CV (done once per job, when the AI is on). */
    private void readJobPage(final WebView view, final String url) {
        if (browserJob.isEmpty() || browserJob.equals(fitAsked)) {
            return;
        }
        final String job = browserJob;
        view.evaluateJavascript(fillScript() + "\n;window.AAFill.page()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                final Map<String, Object> pg = Json.map(Json.tryParse(jsText(raw)));
                if (Json.str(pg, "text").trim().length() < 400 || !job.equals(browserJob)) {
                    return;
                }
                fitAsked = job;
                bridgePool.execute(new Runnable() {
                    @Override
                    public void run() {
                        platform.api.handle("/api/copilot/fit", Json.stringify(Json.obj("id", job, "text", Json.str(pg, "text"),
                                "heading", Json.str(pg, "heading"), "page_title", Json.str(pg, "title"))));
                        js("window.aaRefreshJobs && window.aaRefreshJobs()");
                    }
                });
            }
        });
    }

    /** Fill: your answers go into the form on the page. You check them and send it yourself. */
    private void fillForm() {
        if (browserWeb == null) {
            return;
        }
        final WebView web = browserWeb;
        browserFillBtn.setEnabled(false);
        browserFillBtn.setText("Filling…");
        web.evaluateJavascript(fillScript() + "\n;window.AAFill.collect()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                final Object fields = Json.tryParse(jsText(raw));
                if (!(fields instanceof List) || ((List<?>) fields).isEmpty()) {
                    web.evaluateJavascript("window.AAFill.frames()", new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String fr) {
                            List<Object> frames = Json.arr(Json.tryParse(jsText(fr)));
                            fillDone();
                            if (!frames.isEmpty() && web == browserWeb) {
                                web.loadUrl(String.valueOf(frames.get(0)));
                                Toast.makeText(MainActivity.this, "The form was inside the page, so it is open on its own now. "
                                        + "Tap Fill the form again.", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(MainActivity.this, "No form on this page yet. Tap the site's Apply button, then "
                                        + "Fill the form.", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                    return;
                }
                final String job = browserJob;
                final String title = browserTitleView == null ? "" : String.valueOf(browserTitleView.getText());
                bridgePool.execute(new Runnable() {
                    @Override
                    public void run() {
                        String out = platform.api.handle("/api/fill", Json.stringify(Json.obj("id", job, "title", title,
                                "company", "", "fields", fields)));
                        final Map<String, Object> r = Json.map(Json.tryParse(out));
                        platform.main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (web != browserWeb || destroyed) {
                                    return;
                                }
                                if (Boolean.FALSE.equals(r.get("ok"))) {
                                    fillDone();
                                    Toast.makeText(MainActivity.this, Json.str(r, "error"), Toast.LENGTH_LONG).show();
                                    return;
                                }
                                final List<Object> skipped = Json.arr(r.get("skipped"));
                                web.evaluateJavascript("window.AAFill.apply(" + Json.stringify(r.get("answers")) + ")",
                                        new ValueCallback<String>() {
                                            @Override
                                            public void onReceiveValue(String n) {
                                                fillDone();
                                                int filled = (int) Json.num(Json.obj("n", Json.tryParse(n)), "n", 0);
                                                String msg = filled == 0 ? "Nothing to fill here: the form's fields are filled already, or "
                                                        + "your answers do not cover them."
                                                        : "Filled " + filled + (filled == 1 ? " field" : " fields") + ". Check them"
                                                        + (skipped.isEmpty() ? "" : ", answer " + skipped.size() + " more yourself")
                                                        + ", attach your CV and send it.";
                                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });
    }

    private void fillDone() {
        if (browserFillBtn != null) {
            browserFillBtn.setEnabled(true);
            browserFillBtn.setText("Fill the form");
        }
    }

    /** The site asks for a file: your CV, or another file from the phone. */
    private void chooseSiteFile(final WebChromeClient.FileChooserParams params) {
        Map<String, Object> cv = platform.engine.config.status(platform.engine.config.load());
        final String cvName = Json.str(cv, "cv_name");
        if (!Json.bool(cv, "has_cv")) {
            pickSiteFile(params);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Attach a file")
                .setItems(new CharSequence[]{"Your CV (" + cvName + ")", "Another file…"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            if (browserFiles != null) {
                                browserFiles.onReceiveValue(new Uri[]{FilesProvider.uriForCv(cvName)});
                                browserFiles = null;
                            }
                        } else {
                            pickSiteFile(params);
                        }
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        if (browserFiles != null) {
                            browserFiles.onReceiveValue(null);
                            browserFiles = null;
                        }
                    }
                })
                .show();
    }

    private void pickSiteFile(WebChromeClient.FileChooserParams params) {
        try {
            startActivityForResult(params.createIntent(), REQ_SITE_FILE);
        } catch (ActivityNotFoundException e) {
            if (browserFiles != null) {
                browserFiles.onReceiveValue(null);
                browserFiles = null;
            }
        }
    }

    private void saveBrowserJob() {
        if (browserWeb == null) {
            return;
        }
        final String url = browserWeb.getUrl() == null ? "" : browserWeb.getUrl();
        final String title = browserTitleView == null ? "" : String.valueOf(browserTitleView.getText());
        browserMainBtn.setEnabled(false);
        bridgePool.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, Object> r = Json.map(Json.tryParse(platform.api.handle("/api/copilot/add",
                        Json.stringify(Json.obj("text", title + "\n" + url, "subject", "")))));
                platform.main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (browserMainBtn == null) {
                            return;
                        }
                        browserMainBtn.setEnabled(true);
                        if (Boolean.FALSE.equals(r.get("ok"))) {
                            Toast.makeText(MainActivity.this, Json.str(r, "error"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        Map<String, Object> item = Json.map(r.get("item"));
                        browserJob = Json.str(item, "id");
                        browserJobTitle = Json.str(item, "title");
                        paintBrowserButtons();
                        Toast.makeText(MainActivity.this, Json.bool(item, "again") ? "Already in your jobs." : "Saved to your jobs.",
                                Toast.LENGTH_SHORT).show();
                        js("window.aaRefreshJobs && window.aaRefreshJobs()");
                        if (browserWeb != null) {
                            readJobPage(browserWeb, url);
                        }
                    }
                });
            }
        });
    }

    private void markBrowserApplied() {
        final String job = browserJob;
        browserMainBtn.setEnabled(false);
        bridgePool.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, Object> r = Json.map(Json.tryParse(platform.api.handle("/api/copilot/update",
                        Json.stringify(Json.obj("id", job, "state", "applied")))));
                platform.main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (browserMainBtn != null) {
                            browserMainBtn.setEnabled(true);
                        }
                        if (Boolean.FALSE.equals(r.get("ok"))) {
                            Toast.makeText(MainActivity.this, Json.str(r, "error"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        browserApplied = true;
                        closeBrowser();
                    }
                });
            }
        });
    }

    /** Back in the app: it asks whether you applied, unless you said so already. */
    void closeBrowser() {
        String job = browserJob;
        boolean applied = browserApplied;
        closePanes();
        js("window.aaBrowserClosed && window.aaBrowserClosed(" + Json.stringify(job) + "," + applied + ")");
    }

    // ------------------------------------------------------------------ permissions
    void requestPerm(String which) {
        String pkg = getPackageName();
        try {
            if ("notifications".equals(which)) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED && !askedNotify) {
                    askedNotify = true;
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
                } else if (Build.VERSION.SDK_INT >= 26) {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg));
                } else {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                }
            } else if ("battery".equals(which)) {
                if ("play".equals(BuildConfig.FLAVOR)) {
                    // Google Play build: the list in Settings (asking directly needs a permission Play restricts).
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    Toast.makeText(this, "Pick All apps, then Auto Apply, then Don't optimize.", Toast.LENGTH_LONG).show();
                } else {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)));
                    } catch (ActivityNotFoundException e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                }
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Open Settings > Apps > Auto Apply to change this.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        dash.evaluateJavascript("window.aaRefresh && window.aaRefresh()", null);
    }

    /** Back on Android 13+ (Android 16 no longer calls onBackPressed for apps that target it). */
    static final class Back33 {
        private Back33() {
        }

        static void register(final MainActivity a) {
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            a.handleBack();
                        }
                    });
        }
    }
}
