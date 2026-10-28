package com.nanhe.qalamkar;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int FILE_CHOOSER = 1001;

    private WebView webView;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private long lastInitAt = 0;
    private String pendingText = null;
    private String pendingLang = null;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initTts();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidTTS");
        webView.loadUrl("file:///android_asset/index.html");
    }

    /** (Re)starts the phone's text-to-speech engine. Safe to call again if it stopped. */
    private synchronized void initTts() {
        ttsReady = false;
        lastInitAt = SystemClock.elapsedRealtime();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setSpeechRate(0.8f);
            tts.setPitch(1.05f);
            ttsReady = true;
            if (pendingText != null) {
                String t = pendingText;
                String l = pendingLang;
                pendingText = null;
                pendingLang = null;
                say(t, l);
            }
        } else {
            ttsReady = false;
        }
    }

    private void say(String text, String lang) {
        int result = tts.setLanguage(Locale.forLanguageTag(lang));
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // this phone has no Arabic voice: tell the page so it can show how to install one
            webView.post(new Runnable() {
                @Override
                public void run() {
                    webView.evaluateJavascript("window.__noVoice && window.__noVoice()", null);
                }
            });
            return;
        }
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nq");
        if (r == TextToSpeech.ERROR) {
            // engine stopped working: restart it and say the text once it is back
            pendingText = text;
            pendingLang = lang;
            initTts();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the phone may have stopped the engine while the app was in the background
        if (!ttsReady && SystemClock.elapsedRealtime() - lastInitAt > 3000) {
            initTts();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    /** Called from the page: AndroidTTS.available(), speak(text, lang), hasLang(lang), installVoice(). */
    private class Bridge {
        @JavascriptInterface
        public boolean available() {
            if (!ttsReady && SystemClock.elapsedRealtime() - lastInitAt > 3000) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        initTts();
                    }
                });
            }
            return ttsReady;
        }

        /** true if the phone's engine can speak this language (true while the engine is still starting). */
        @JavascriptInterface
        public boolean hasLang(String lang) {
            if (!ttsReady) return true;
            try {
                int a = tts.isLanguageAvailable(Locale.forLanguageTag(lang));
                return a >= TextToSpeech.LANG_AVAILABLE;
            } catch (Exception e) {
                return false;
            }
        }

        /** Opens the phone's screen for downloading text-to-speech voice data (Arabic). */
        @JavascriptInterface
        public void installVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent s = new Intent("com.android.settings.TTS_SETTINGS");
                            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(s);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text, final String lang) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!ttsReady) {
                        pendingText = text;
                        pendingLang = lang;
                        if (SystemClock.elapsedRealtime() - lastInitAt > 3000) {
                            initTts();
                        }
                        return;
                    }
                    say(text, lang);
                }
            });
        }
    }
}
