package com.nissshh.campaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri videoUri = result.getData().getData();
                        if (videoUri != null) {
                            handleImportedVideo(videoUri);
                        }
                    }
                }
        );

        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                fileUploadCallback = filePathCallback;
                launchVideoPicker();
                return true;
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void launchVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        videoPickerLauncher.launch(intent);
    }

    private void handleImportedVideo(Uri uri) {
        try {
            File dest = new File(getFilesDir(), "custom_video_" + System.currentTimeMillis() + ".mp4");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            long durationMs = 23100L;
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(dest.getAbsolutePath());
                String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durStr != null) {
                    durationMs = Long.parseLong(durStr);
                }
                mmr.release();
            } catch (Exception ignored) {}

            SharedPreferences.Editor editor = WallpaperPreferences.getPrefs(this).edit();
            editor.putString(WallpaperPreferences.KEY_CUSTOM_VIDEO_PATH, dest.getAbsolutePath());
            editor.putLong(WallpaperPreferences.KEY_LOOP_START_MS, 0L);
            editor.putLong(WallpaperPreferences.KEY_LOOP_END_MS, durationMs);
            editor.putLong(WallpaperPreferences.KEY_HOME_LOOP_START_MS, 0L);
            editor.putLong(WallpaperPreferences.KEY_HOME_LOOP_END_MS, durationMs);
            editor.apply();

            final long finalDurMs = durationMs;
            final String videoPath = dest.getAbsolutePath();
            runOnUiThread(() -> {
                Toast.makeText(this, "Video imported successfully!", Toast.LENGTH_SHORT).show();
                webView.evaluateJavascript("onVideoImported('" + videoPath + "', " + finalDurMs + ");", null);
            });

        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Failed to import video: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    public class WebAppInterface {
        private final Context context;

        WebAppInterface(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void openExternalUrl(String url) {
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(browserIntent);
            } catch (Exception e) {
                Toast.makeText(context, "Could not open browser: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void triggerUploadVideo() {
            runOnUiThread(MainActivity.this::launchVideoPicker);
        }

        @JavascriptInterface
        public void resetToDefaultVideo() {
            SharedPreferences.Editor editor = WallpaperPreferences.getPrefs(context).edit();
            editor.putString(WallpaperPreferences.KEY_CUSTOM_VIDEO_PATH, "");
            editor.putLong(WallpaperPreferences.KEY_LOOP_START_MS, WallpaperPreferences.DEFAULT_LOCK_LOOP_START_MS);
            editor.putLong(WallpaperPreferences.KEY_LOOP_END_MS, WallpaperPreferences.DEFAULT_LOCK_LOOP_END_MS);
            editor.putFloat(WallpaperPreferences.KEY_LOCK_CROP_SCALE, 1.0f);
            editor.putFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_X, 0.0f);
            editor.putFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_Y, 0.0f);

            editor.putBoolean(WallpaperPreferences.KEY_HOME_SEPARATE_LOOP, true);
            editor.putLong(WallpaperPreferences.KEY_HOME_LOOP_START_MS, WallpaperPreferences.DEFAULT_HOME_LOOP_START_MS);
            editor.putLong(WallpaperPreferences.KEY_HOME_LOOP_END_MS, WallpaperPreferences.DEFAULT_HOME_LOOP_END_MS);
            editor.putFloat(WallpaperPreferences.KEY_HOME_CROP_SCALE, 1.0f);
            editor.putFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_X, 0.0f);
            editor.putFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_Y, 0.0f);
            editor.apply();

            runOnUiThread(() -> {
                Toast.makeText(context, "Reset to default video", Toast.LENGTH_SHORT).show();
                webView.evaluateJavascript("onDefaultVideoRestored();", null);
            });
        }

        @JavascriptInterface
        public String getSettings() {
            SharedPreferences prefs = WallpaperPreferences.getPrefs(context);
            JSONObject json = new JSONObject();
            try {
                json.put("customVideoPath", prefs.getString(WallpaperPreferences.KEY_CUSTOM_VIDEO_PATH, ""));
                json.put("videoEffect", prefs.getInt(WallpaperPreferences.KEY_VIDEO_EFFECT, 0));
                json.put("brightness", (double) prefs.getFloat(WallpaperPreferences.KEY_EFFECT_BRIGHTNESS, 0.0f));
                json.put("contrast", (double) prefs.getFloat(WallpaperPreferences.KEY_EFFECT_CONTRAST, 1.0f));
                json.put("saturation", (double) prefs.getFloat(WallpaperPreferences.KEY_EFFECT_SATURATION, 1.0f));
                json.put("vignette", (double) prefs.getFloat(WallpaperPreferences.KEY_EFFECT_VIGNETTE, 0.0f));

                // Lockscreen Settings
                json.put("loopStartMs", prefs.getLong(WallpaperPreferences.KEY_LOOP_START_MS, WallpaperPreferences.DEFAULT_LOCK_LOOP_START_MS));
                json.put("loopEndMs", prefs.getLong(WallpaperPreferences.KEY_LOOP_END_MS, WallpaperPreferences.DEFAULT_LOCK_LOOP_END_MS));
                json.put("lockCropScale", (double) prefs.getFloat(WallpaperPreferences.KEY_LOCK_CROP_SCALE, 1.0f));
                json.put("lockCropOffsetX", (double) prefs.getFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_X, 0.0f));
                json.put("lockCropOffsetY", (double) prefs.getFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_Y, 0.0f));

                // Homescreen Settings
                json.put("homeSeparateLoop", prefs.getBoolean(WallpaperPreferences.KEY_HOME_SEPARATE_LOOP, true));
                json.put("homeLoopStartMs", prefs.getLong(WallpaperPreferences.KEY_HOME_LOOP_START_MS, WallpaperPreferences.DEFAULT_HOME_LOOP_START_MS));
                json.put("homeLoopEndMs", prefs.getLong(WallpaperPreferences.KEY_HOME_LOOP_END_MS, WallpaperPreferences.DEFAULT_HOME_LOOP_END_MS));
                json.put("homeCropScale", (double) prefs.getFloat(WallpaperPreferences.KEY_HOME_CROP_SCALE, 1.0f));
                json.put("homeCropOffsetX", (double) prefs.getFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_X, 0.0f));
                json.put("homeCropOffsetY", (double) prefs.getFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_Y, 0.0f));

                // General Playback
                json.put("playOnWake", prefs.getBoolean(WallpaperPreferences.KEY_PLAY_ON_WAKE, true));
                json.put("isMuted", prefs.getBoolean(WallpaperPreferences.KEY_MUTED, true));
                json.put("playbackSpeed", (double) prefs.getFloat(WallpaperPreferences.KEY_PLAYBACK_SPEED, 1.0f));
                json.put("cropMode", prefs.getString(WallpaperPreferences.KEY_CROP_MODE, "CROP_CENTER"));
                json.put("cropScale", (double) prefs.getFloat(WallpaperPreferences.KEY_CROP_SCALE, 1.0f));
                json.put("cropOffsetX", (double) prefs.getFloat(WallpaperPreferences.KEY_CROP_OFFSET_X, 0.0f));
                json.put("cropOffsetY", (double) prefs.getFloat(WallpaperPreferences.KEY_CROP_OFFSET_Y, 0.0f));
                json.put("totalDurationMs", WallpaperPreferences.DEFAULT_VIDEO_DURATION_MS);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return json.toString();
        }

        @JavascriptInterface
        public void saveSettings(String jsonStr) {
            try {
                JSONObject json = new JSONObject(jsonStr);
                SharedPreferences.Editor editor = WallpaperPreferences.getPrefs(context).edit();

                if (json.has("videoEffect")) editor.putInt(WallpaperPreferences.KEY_VIDEO_EFFECT, json.getInt("videoEffect"));
                if (json.has("brightness")) editor.putFloat(WallpaperPreferences.KEY_EFFECT_BRIGHTNESS, (float) json.getDouble("brightness"));
                if (json.has("contrast")) editor.putFloat(WallpaperPreferences.KEY_EFFECT_CONTRAST, (float) json.getDouble("contrast"));
                if (json.has("saturation")) editor.putFloat(WallpaperPreferences.KEY_EFFECT_SATURATION, (float) json.getDouble("saturation"));
                if (json.has("vignette")) editor.putFloat(WallpaperPreferences.KEY_EFFECT_VIGNETTE, (float) json.getDouble("vignette"));

                // Lockscreen
                if (json.has("loopStartMs")) editor.putLong(WallpaperPreferences.KEY_LOOP_START_MS, json.getLong("loopStartMs"));
                if (json.has("loopEndMs")) editor.putLong(WallpaperPreferences.KEY_LOOP_END_MS, json.getLong("loopEndMs"));
                if (json.has("lockCropScale")) editor.putFloat(WallpaperPreferences.KEY_LOCK_CROP_SCALE, (float) json.getDouble("lockCropScale"));
                if (json.has("lockCropOffsetX")) editor.putFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_X, (float) json.getDouble("lockCropOffsetX"));
                if (json.has("lockCropOffsetY")) editor.putFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_Y, (float) json.getDouble("lockCropOffsetY"));

                // Homescreen
                if (json.has("homeSeparateLoop")) editor.putBoolean(WallpaperPreferences.KEY_HOME_SEPARATE_LOOP, json.getBoolean("homeSeparateLoop"));
                if (json.has("homeLoopStartMs")) editor.putLong(WallpaperPreferences.KEY_HOME_LOOP_START_MS, json.getLong("homeLoopStartMs"));
                if (json.has("homeLoopEndMs")) editor.putLong(WallpaperPreferences.KEY_HOME_LOOP_END_MS, json.getLong("homeLoopEndMs"));
                if (json.has("homeCropScale")) editor.putFloat(WallpaperPreferences.KEY_HOME_CROP_SCALE, (float) json.getDouble("homeCropScale"));
                if (json.has("homeCropOffsetX")) editor.putFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_X, (float) json.getDouble("homeCropOffsetX"));
                if (json.has("homeCropOffsetY")) editor.putFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_Y, (float) json.getDouble("homeCropOffsetY"));

                // Global Playback
                if (json.has("playOnWake")) editor.putBoolean(WallpaperPreferences.KEY_PLAY_ON_WAKE, json.getBoolean("playOnWake"));
                if (json.has("isMuted")) editor.putBoolean(WallpaperPreferences.KEY_MUTED, json.getBoolean("isMuted"));
                if (json.has("playbackSpeed")) editor.putFloat(WallpaperPreferences.KEY_PLAYBACK_SPEED, (float) json.getDouble("playbackSpeed"));
                if (json.has("cropMode")) editor.putString(WallpaperPreferences.KEY_CROP_MODE, json.getString("cropMode"));
                if (json.has("cropScale")) editor.putFloat(WallpaperPreferences.KEY_CROP_SCALE, (float) json.getDouble("cropScale"));
                if (json.has("cropOffsetX")) editor.putFloat(WallpaperPreferences.KEY_CROP_OFFSET_X, (float) json.getDouble("cropOffsetX"));
                if (json.has("cropOffsetY")) editor.putFloat(WallpaperPreferences.KEY_CROP_OFFSET_Y, (float) json.getDouble("cropOffsetY"));

                editor.apply();

                runOnUiThread(() -> Toast.makeText(context, "Settings Applied Successfully!", Toast.LENGTH_SHORT).show());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @JavascriptInterface
        public void openWallpaperChooser() {
            try {
                Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                intent.putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(context, VideoWallpaperService.class)
                );
                context.startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent fallbackIntent = new Intent("android.service.wallpaper.LIVE_WALLPAPER_CHOOSER");
                    context.startActivity(fallbackIntent);
                } catch (Exception e2) {
                    Toast.makeText(context, "Please set CamPaper via Wallpaper Settings", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
