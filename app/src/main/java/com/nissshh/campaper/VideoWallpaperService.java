package com.nissshh.campaper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;

public class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "CamPaperService";

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    class VideoEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private ExoPlayer player;
        private SurfaceHolder surfaceHolder;
        private KeyguardManager keyguardManager;
        private boolean isVisible = false;

        private int screenWidth = 1440;
        private int screenHeight = 3120;
        private int videoWidth = 2560;
        private int videoHeight = 1440;

        // OpenGL rendering thread
        private HandlerThread renderThread;
        private Handler renderHandler;
        private VideoGLRenderer glRenderer;
        private Surface glVideoSurface;

        // EGL objects
        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        // Video source
        private String customVideoPath = "";

        // Effects
        private int videoEffect = 0;
        private float brightness = 0.0f;
        private float contrast = 1.0f;
        private float saturation = 1.0f;
        private float vignette = 0.0f;

        // Lockscreen Loop & Crop
        private long lockLoopStartMs = 0;
        private long lockLoopEndMs = WallpaperPreferences.DEFAULT_VIDEO_DURATION_MS;
        private float lockCropScale = 1.0f;
        private float lockCropOffsetX = 0.0f;
        private float lockCropOffsetY = 0.0f;

        // Homescreen Loop & Crop
        private boolean homeSeparateLoop = true;
        private long homeLoopStartMs = 4000L;
        private long homeLoopEndMs = 12000L;
        private float homeCropScale = 1.0f;
        private float homeCropOffsetX = 0.0f;
        private float homeCropOffsetY = 0.0f;

        private boolean playOnWake = true;
        private boolean isMuted = true;
        private float playbackSpeed = 1.0f;

        // Screen state broadcast receiver
        private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) ||
                    Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    Log.d(TAG, "Screen wake event detected: " + intent.getAction());
                    updateActiveCrop();
                    handleWakePlayback();
                }
            }
        };

        private final Handler loopHandler = new Handler(Looper.getMainLooper());
        private final Runnable loopCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && player.isPlaying()) {
                    long currentPos = player.getCurrentPosition();
                    long targetStart = getCurrentActiveLoopStart();
                    long targetEnd = getCurrentActiveLoopEnd();

                    if (currentPos >= targetEnd || currentPos < targetStart) {
                        player.seekTo(targetStart);
                    }
                }
                if (isVisible) {
                    loopHandler.postDelayed(this, 100);
                }
            }
        };

        private final Runnable drawRunnable = new Runnable() {
            @Override
            public void run() {
                if (isVisible && glRenderer != null && eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                    glRenderer.drawFrame();
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                    if (renderHandler != null) {
                        renderHandler.postDelayed(this, 16); // 60 FPS
                    }
                }
            }
        };

        private boolean isHomeScreenActive() {
            if (keyguardManager == null) {
                keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            }
            if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                return false;
            }
            return true;
        }

        private long getCurrentActiveLoopStart() {
            if (homeSeparateLoop && isHomeScreenActive()) {
                return homeLoopStartMs;
            }
            return lockLoopStartMs;
        }

        private long getCurrentActiveLoopEnd() {
            if (homeSeparateLoop && isHomeScreenActive()) {
                return homeLoopEndMs;
            }
            return lockLoopEndMs;
        }

        private void updateActiveCrop() {
            if (glRenderer != null) {
                if (isHomeScreenActive()) {
                    glRenderer.setCropParameters(homeCropScale, homeCropOffsetX, homeCropOffsetY);
                } else {
                    glRenderer.setCropParameters(lockCropScale, lockCropOffsetX, lockCropOffsetY);
                }
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            this.surfaceHolder = surfaceHolder;
            keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            loadPreferences();
            WallpaperPreferences.getPrefs(VideoWallpaperService.this)
                    .registerOnSharedPreferenceChangeListener(this);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenStateReceiver, filter);
        }

        private void loadPreferences() {
            SharedPreferences prefs = WallpaperPreferences.getPrefs(VideoWallpaperService.this);
            customVideoPath = prefs.getString(WallpaperPreferences.KEY_CUSTOM_VIDEO_PATH, "");

            videoEffect = prefs.getInt(WallpaperPreferences.KEY_VIDEO_EFFECT, 0);
            brightness = prefs.getFloat(WallpaperPreferences.KEY_EFFECT_BRIGHTNESS, 0.0f);
            contrast = prefs.getFloat(WallpaperPreferences.KEY_EFFECT_CONTRAST, 1.0f);
            saturation = prefs.getFloat(WallpaperPreferences.KEY_EFFECT_SATURATION, 1.0f);
            vignette = prefs.getFloat(WallpaperPreferences.KEY_EFFECT_VIGNETTE, 0.0f);

            // Lockscreen
            lockLoopStartMs = prefs.getLong(WallpaperPreferences.KEY_LOOP_START_MS, WallpaperPreferences.DEFAULT_LOCK_LOOP_START_MS);
            lockLoopEndMs = prefs.getLong(WallpaperPreferences.KEY_LOOP_END_MS, WallpaperPreferences.DEFAULT_LOCK_LOOP_END_MS);
            if (lockLoopEndMs <= lockLoopStartMs) {
                lockLoopEndMs = WallpaperPreferences.DEFAULT_LOCK_LOOP_END_MS;
            }
            lockCropScale = prefs.getFloat(WallpaperPreferences.KEY_LOCK_CROP_SCALE, prefs.getFloat(WallpaperPreferences.KEY_CROP_SCALE, 1.0f));
            lockCropOffsetX = prefs.getFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_X, prefs.getFloat(WallpaperPreferences.KEY_CROP_OFFSET_X, 0.0f));
            lockCropOffsetY = prefs.getFloat(WallpaperPreferences.KEY_LOCK_CROP_OFFSET_Y, prefs.getFloat(WallpaperPreferences.KEY_CROP_OFFSET_Y, 0.0f));

            // Homescreen
            homeSeparateLoop = prefs.getBoolean(WallpaperPreferences.KEY_HOME_SEPARATE_LOOP, true);
            homeLoopStartMs = prefs.getLong(WallpaperPreferences.KEY_HOME_LOOP_START_MS, WallpaperPreferences.DEFAULT_HOME_LOOP_START_MS);
            homeLoopEndMs = prefs.getLong(WallpaperPreferences.KEY_HOME_LOOP_END_MS, WallpaperPreferences.DEFAULT_HOME_LOOP_END_MS);
            if (homeLoopEndMs <= homeLoopStartMs) {
                homeLoopEndMs = WallpaperPreferences.DEFAULT_HOME_LOOP_END_MS;
            }
            homeCropScale = prefs.getFloat(WallpaperPreferences.KEY_HOME_CROP_SCALE, prefs.getFloat(WallpaperPreferences.KEY_CROP_SCALE, 1.0f));
            homeCropOffsetX = prefs.getFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_X, prefs.getFloat(WallpaperPreferences.KEY_CROP_OFFSET_X, 0.0f));
            homeCropOffsetY = prefs.getFloat(WallpaperPreferences.KEY_HOME_CROP_OFFSET_Y, prefs.getFloat(WallpaperPreferences.KEY_CROP_OFFSET_Y, 0.0f));

            playOnWake = prefs.getBoolean(WallpaperPreferences.KEY_PLAY_ON_WAKE, true);
            isMuted = prefs.getBoolean(WallpaperPreferences.KEY_MUTED, true);
            playbackSpeed = prefs.getFloat(WallpaperPreferences.KEY_PLAYBACK_SPEED, 1.0f);

            if (glRenderer != null) {
                updateActiveCrop();
                glRenderer.setEffectParameters(videoEffect, brightness, contrast, saturation, vignette);
            }
            if (player != null) {
                player.setPlaybackSpeed(playbackSpeed);
                player.setVolume(isMuted ? 0f : 1f);
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            String oldPath = customVideoPath;
            loadPreferences();
            if (WallpaperPreferences.KEY_CUSTOM_VIDEO_PATH.equals(key) && !oldPath.equals(customVideoPath)) {
                initializePlayer();
            } else if (player != null) {
                long currentPos = player.getCurrentPosition();
                long start = getCurrentActiveLoopStart();
                long end = getCurrentActiveLoopEnd();
                if (currentPos < start || currentPos > end) {
                    player.seekTo(start);
                }
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            this.surfaceHolder = holder;
            startRenderThread();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            this.screenWidth = width;
            this.screenHeight = height;
            if (glRenderer != null) {
                glRenderer.setScreenDimensions(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            stopRenderThread();
            releasePlayer();
        }

        private void handleWakePlayback() {
            updateActiveCrop();
            if (player == null) {
                initializePlayer();
            } else {
                if (playOnWake) {
                    player.seekTo(getCurrentActiveLoopStart());
                }
                player.play();
            }
            loopHandler.removeCallbacks(loopCheckRunnable);
            loopHandler.post(loopCheckRunnable);

            if (renderHandler != null) {
                renderHandler.removeCallbacks(drawRunnable);
                renderHandler.post(drawRunnable);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.isVisible = visible;
            if (visible) {
                handleWakePlayback();
            } else {
                loopHandler.removeCallbacks(loopCheckRunnable);
                if (renderHandler != null) {
                    renderHandler.removeCallbacks(drawRunnable);
                }
                if (player != null) {
                    player.pause();
                }
            }
        }

        private void startRenderThread() {
            renderThread = new HandlerThread("CamPaperGLThread");
            renderThread.start();
            renderHandler = new Handler(renderThread.getLooper());

            renderHandler.post(() -> {
                initEGL();
                glRenderer = new VideoGLRenderer();
                glRenderer.setScreenDimensions(screenWidth, screenHeight);
                glRenderer.setVideoDimensions(videoWidth, videoHeight);
                updateActiveCrop();
                glRenderer.setEffectParameters(videoEffect, brightness, contrast, saturation, vignette);
                glVideoSurface = glRenderer.initGL();

                new Handler(Looper.getMainLooper()).post(this::initializePlayer);
            });
        }

        private void stopRenderThread() {
            if (renderHandler != null) {
                renderHandler.removeCallbacks(drawRunnable);
                renderHandler.post(() -> {
                    if (glRenderer != null) {
                        glRenderer.release();
                        glRenderer = null;
                    }
                    destroyEGL();
                });
            }
            if (renderThread != null) {
                renderThread.quitSafely();
                renderThread = null;
            }
        }

        private void initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0);

            int[] contextAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceHolder.getSurface(), surfaceAttribs, 0);

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }

        private void destroyEGL() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }
                EGL14.eglTerminate(eglDisplay);
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
        }

        private void initializePlayer() {
            if (player != null) {
                player.release();
            }

            Context context = VideoWallpaperService.this;
            player = new ExoPlayer.Builder(context).build();

            Uri videoUri;
            if (customVideoPath != null && !customVideoPath.isEmpty() && new File(customVideoPath).exists()) {
                videoUri = Uri.fromFile(new File(customVideoPath));
            } else {
                videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.wallpaper_video);
            }

            MediaItem mediaItem = MediaItem.fromUri(videoUri);
            player.setMediaItem(mediaItem);
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            player.setPlaybackSpeed(playbackSpeed);
            player.setVolume(isMuted ? 0f : 1f);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_ENDED) {
                        player.seekTo(getCurrentActiveLoopStart());
                        player.play();
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    if (size.width > 0 && size.height > 0) {
                        videoWidth = size.width;
                        videoHeight = size.height;
                        if (glRenderer != null) {
                            glRenderer.setVideoDimensions(videoWidth, videoHeight);
                        }
                    }
                }
            });

            if (glVideoSurface != null && glVideoSurface.isValid()) {
                player.setVideoSurface(glVideoSurface);
            }

            player.prepare();
            player.seekTo(getCurrentActiveLoopStart());

            if (isVisible) {
                player.play();
                loopHandler.removeCallbacks(loopCheckRunnable);
                loopHandler.post(loopCheckRunnable);

                if (renderHandler != null) {
                    renderHandler.removeCallbacks(drawRunnable);
                    renderHandler.post(drawRunnable);
                }
            }
        }

        private void releasePlayer() {
            loopHandler.removeCallbacks(loopCheckRunnable);
            if (player != null) {
                player.setVideoSurface(null);
                player.release();
                player = null;
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception ignored) {}
            WallpaperPreferences.getPrefs(VideoWallpaperService.this)
                    .unregisterOnSharedPreferenceChangeListener(this);
            stopRenderThread();
            releasePlayer();
        }
    }
}
