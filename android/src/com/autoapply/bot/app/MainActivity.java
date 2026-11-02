package com.autoapply.bot.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.autoapply.bot.core.Json;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app's screen: the dashboard (same as the PC version), the LinkedIn sign-in screen and Live view. */
public final class MainActivity extends Activity {
    static final String ASSET_HOST = "appassets.androidplatform.net";
    static final int REQ_FILE = 1;
    static final int REQ_NOTIFY = 2;

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
        styleBars();
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

    private void handle(Intent intent) {
        String open = intent == null ? null : intent.getStringExtra("open");
        if ("signin".equals(open)) {
            showSignIn();
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
