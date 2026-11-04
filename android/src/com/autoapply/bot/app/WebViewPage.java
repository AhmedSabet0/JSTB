package com.autoapply.bot.app;

import android.app.Presentation;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Page;
import com.autoapply.bot.core.Util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bot's LinkedIn page: a WebView with a desktop layout, never shown to you (unless you open Live view).
 *
 * <p>A WebView that is not on any screen is never drawn, and then LinkedIn's newer pages stall: animation frames,
 * visibility and resize observers only run while a page is drawn, and the Easy Apply window waits for them. So the
 * page sits on a private, invisible screen of its own (a virtual display that nobody sees), where it is drawn like in
 * a browser on a real screen, also while the app is in the background.
 */
public final class WebViewPage implements Page {
    /** CSS size of the page, like a laptop screen, so LinkedIn serves its desktop layout. */
    static final int CSS_WIDTH = 1280;
    static final int CSS_HEIGHT = 900;

    final Handler main;
    final MutableContextWrapper context;
    WebView web;
    volatile boolean closed;
    volatile String currentUrl = "about:blank";
    volatile int navSeq = 0;
    volatile int startedSeq = 0;
    volatile String mainError;
    int widthPx;
    int heightPx;
    ViewGroup shownIn;

    // the invisible screen
    private HandlerThread frameThread;
    private ImageReader frames;
    private VirtualDisplay screen;
    private Presentation window;
    private FrameLayout holder;
    /** True when the page is on its invisible screen (false: the phone did not allow it; the page is not drawn). */
    volatile boolean onScreen;
    volatile String screenProblem = "";

    WebViewPage(Context appContext, Handler main) {
        this.main = main;
        this.context = new MutableContextWrapper(appContext);
        final CountDownLatch made = new CountDownLatch(1);
        final RuntimeException[] failed = new RuntimeException[1];
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    create();
                } catch (RuntimeException e) {
                    failed[0] = e;
                } finally {
                    made.countDown();
                }
            }
        });
        try {
            if (!made.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The browser did not start");
            }
        } catch (InterruptedException e) {
            throw new Util.StopRequested();
        }
        if (failed[0] != null) {
            throw failed[0];
        }
    }

    /** Chrome's desktop user agent with this phone's real Chrome version. */
    static String desktopUserAgent(String mobile) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(mobile == null ? "" : mobile);
        String major = m.find() ? m.group(1) : "130";
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major
                + ".0.0.0 Safari/537.36";
    }

    static void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
    }

    private void create() {
        web = new WebView(context);
        configure(web);
        WebSettings s = web.getSettings();
        s.setLoadWithOverviewMode(false);
        s.setBlockNetworkImage(true);  // the bot does not need pictures: less mobile data
        if (Build.VERSION.SDK_INT >= 26) {
            // keep the page's process as important as the app while it is hidden (the app runs a foreground service)
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url;
                startedSeq = navSeq;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                currentUrl = url;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainError = String.valueOf(error.getDescription());
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                // never hand LinkedIn links to the LinkedIn app or other apps
                return !"https".equals(scheme) && !"http".equals(scheme);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                closed = true;  // the bot opens a fresh page
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String dflt, JsPromptResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }
        });
        float density = context.getResources().getDisplayMetrics().density;
        widthPx = Math.round(CSS_WIDTH * density);
        heightPx = Math.round(CSS_HEIGHT * density);
        if (!putOnScreen()) {
            layoutDetached();
        }
        web.onResume();
        web.resumeTimers();
        claimFocus();
    }

    private void layoutDetached() {
        web.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        web.layout(0, 0, widthPx, heightPx);
    }

    // ------------------------------------------------------------------ the invisible screen
    /** Put the page on its own invisible screen (created the first time). False if the phone does not allow it. */
    private boolean putOnScreen() {
        try {
            if (window == null) {
                makeScreen();
            }
            detach();
            context.setBaseContext(window.getContext());
            holder.addView(web, new FrameLayout.LayoutParams(widthPx, heightPx));
            onScreen = true;
            return true;
        } catch (RuntimeException e) {
            screenProblem = Util.shortError(e, 120);
            releaseScreen();
            detach();
            context.setBaseContext(context.getApplicationContext());
            return false;
        }
    }

    private void makeScreen() {
        Context app = context.getApplicationContext();
        frameThread = new HandlerThread("bot-page-frames");
        frameThread.start();
        // What is drawn on the invisible screen is thrown away right away.
        frames = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 3);
        frames.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                } catch (RuntimeException e) {
                    // the screen is closing
                } finally {
                    if (img != null) {
                        img.close();
                    }
                }
            }
        }, new Handler(frameThread.getLooper()));
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        screen = dm.createVirtualDisplay("JSTB bot page", widthPx, heightPx,
                app.getResources().getDisplayMetrics().densityDpi, frames.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        if (screen == null) {
            throw new IllegalStateException("no virtual display");
        }
        window = new Presentation(app, screen.getDisplay());
        Window w = window.getWindow();
        if (w == null) {
            throw new IllegalStateException("no window for the virtual display");
        }
        // A private screen owned by this app; it never takes the keyboard or touches from the apps you use.
        w.setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
        w.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.setCancelable(false);
        holder = new FrameLayout(window.getContext());
        window.setContentView(holder, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.show();
    }

    private void releaseScreen() {
        onScreen = false;
        try {
            if (window != null) {
                window.dismiss();
            }
        } catch (RuntimeException e) {
            // already gone
        }
        window = null;
        holder = null;
        if (screen != null) {
            screen.release();
            screen = null;
        }
        if (frames != null) {
            frames.close();
            frames = null;
        }
        if (frameThread != null) {
            frameThread.quitSafely();
            frameThread = null;
        }
    }

    private void detach() {
        ViewParent parent = web.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(web);
        }
    }

    /** The page acts as if its window had the focus, as for a person using it, without taking the keyboard or the
     *  focus away from the apps you use (its screen cannot be focused). */
    private void claimFocus() {
        try {
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.requestFocus();
            web.onWindowFocusChanged(true);
        } catch (RuntimeException e) {
            // optional
        }
    }

    String screenInfo() {
        return onScreen ? "drawn on its own invisible screen" : "not drawn (no invisible screen: " + screenProblem + ")";
    }

    // ------------------------------------------------------------------ Live view
    /** Show the page inside `box`, scaled down to fit its width (the page keeps its desktop size). */
    void showIn(final ViewGroup box, final Context activity) {
        if (closed || web == null) {
            return;
        }
        detach();
        shownIn = null;
        context.setBaseContext(activity);
        int boxWidth = box.getWidth() > 0 ? box.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        float scale = boxWidth / (float) widthPx;
        web.setPivotX(0);
        web.setPivotY(0);
        web.setScaleX(scale);
        web.setScaleY(scale);
        box.addView(web, new ViewGroup.LayoutParams(widthPx, heightPx));
        shownIn = box;
    }

    /** Back to the invisible screen. */
    void hide() {
        if (web == null || shownIn == null) {
            return;
        }
        shownIn.removeView(web);
        shownIn = null;
        web.setScaleX(1);
        web.setScaleY(1);
        if (!putOnScreen()) {
            layoutDetached();
        }
        web.onResume();
        claimFocus();
    }

    // ------------------------------------------------------------------ Page
    @Override
    public String eval(final String js) {
        if (closed) {
            throw new Util.BrowserClosed("The bot's page was closed");
        }
        final String[] out = {"null"};
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (closed || web == null) {
                    done.countDown();
                    return;
                }
                web.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        out[0] = value == null ? "null" : value;
                        done.countDown();
                    }
                });
            }
        });
        try {
            if (!done.await(45, TimeUnit.SECONDS)) {
                return "null";
            }
        } catch (InterruptedException e) {
            throw new Util.StopRequested();
        }
        if (closed) {
            throw new Util.BrowserClosed("The bot's page was closed");
        }
        return out[0];
    }

    @Override
    public void navigate(final String url, double timeoutSeconds) {
        if (closed) {
            throw new Util.BrowserClosed("The bot's page was closed");
        }
        final int seq = navSeq + 1;
        navSeq = seq;
        mainError = null;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null && !closed) {
                    web.loadUrl(url);
                }
            }
        });
        long end = System.currentTimeMillis() + (long) (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < end) {
            Util.realSleep(0.2);
            if (closed) {
                throw new Util.BrowserClosed("The bot's page was closed");
            }
            String err = mainError;
            if (err != null) {  // no connection, DNS error...: WebView shows its own error page
                throw new RuntimeException("Could not load the page: " + err);
            }
            if (startedSeq >= seq) {
                String state = eval("document.readyState");
                if ("\"interactive\"".equals(state) || "\"complete\"".equals(state)) {
                    return;
                }
            }
        }
        if (mainError != null) {
            throw new RuntimeException("Could not load the page: " + mainError);
        }
        throw new RuntimeException("The page took too long to load");
    }

    @Override
    public String url() {
        Object v = Json.tryParse(eval("location.href"));
        return v instanceof String && !((String) v).isEmpty() ? (String) v : currentUrl;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** A finger tap: touch events sent to the page's view (the page sees real, trusted input). */
    @Override
    public boolean tap(final double x, final double y, final double viewportWidth) {
        if (closed) {
            throw new Util.BrowserClosed("The bot's page was closed");
        }
        final boolean[] sent = {false};
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (web == null || closed || web.getWidth() <= 0) {
                        return;
                    }
                    float scale = viewportWidth > 0 ? web.getWidth() / (float) viewportWidth
                            : context.getResources().getDisplayMetrics().density;
                    final float px = (float) (x * scale);
                    final float py = (float) (y * scale);
                    final long downAt = SystemClock.uptimeMillis();
                    touch(MotionEvent.ACTION_DOWN, downAt, downAt, px, py);
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (web != null && !closed) {
                                touch(MotionEvent.ACTION_UP, downAt, SystemClock.uptimeMillis(), px, py);
                            }
                        }
                    }, 70);
                    sent[0] = true;
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new Util.StopRequested();
        }
        Util.realSleep(0.25);  // let the finger lift
        return sent[0];
    }

    private void touch(int action, long downAt, long at, float x, float y) {
        MotionEvent e = MotionEvent.obtain(downAt, at, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(e);
        e.recycle();
    }

    void destroy() {
        closed = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    detach();
                    shownIn = null;
                    web.stopLoading();
                    web.destroy();
                    web = null;
                }
                releaseScreen();
            }
        });
    }

    static boolean isLinkedIn(Uri u) {
        String host = u == null ? null : u.getHost();
        return host != null && (host.equals("linkedin.com") || host.endsWith(".linkedin.com"));
    }
}
