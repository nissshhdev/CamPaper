package com.nissshh.campaper;

import android.content.Context;
import android.content.SharedPreferences;

public class WallpaperPreferences {
    private static final String PREF_NAME = "campaper_prefs";

    // Video Source
    public static final String KEY_CUSTOM_VIDEO_PATH = "custom_video_path";

    // Video Visual Effects: 0=None, 1=Vibrant/Cinema, 2=Monochrome/Noir, 3=Cyberpunk/Neon, 4=Warm Vintage, 5=Cool Vapor
    public static final String KEY_VIDEO_EFFECT = "video_effect";
    public static final String KEY_EFFECT_BRIGHTNESS = "effect_brightness"; // -0.5 to 0.5
    public static final String KEY_EFFECT_CONTRAST = "effect_contrast";     // 0.5 to 2.0
    public static final String KEY_EFFECT_SATURATION = "effect_saturation"; // 0.0 to 2.5
    public static final String KEY_EFFECT_VIGNETTE = "effect_vignette";     // 0.0 to 1.0

    // Common / Global Crop settings
    public static final String KEY_CROP_MODE = "crop_mode";
    public static final String KEY_CROP_SCALE = "crop_scale";
    public static final String KEY_CROP_OFFSET_X = "crop_offset_x";
    public static final String KEY_CROP_OFFSET_Y = "crop_offset_y";

    // Lockscreen Specific Settings
    public static final String KEY_LOOP_START_MS = "loop_start_ms";
    public static final String KEY_LOOP_END_MS = "loop_end_ms";
    public static final String KEY_LOCK_CROP_SCALE = "lock_crop_scale";
    public static final String KEY_LOCK_CROP_OFFSET_X = "lock_crop_offset_x";
    public static final String KEY_LOCK_CROP_OFFSET_Y = "lock_crop_offset_y";

    // Homescreen Specific Settings
    public static final String KEY_HOME_SEPARATE_LOOP = "home_separate_loop";
    public static final String KEY_HOME_LOOP_START_MS = "home_loop_start_ms";
    public static final String KEY_HOME_LOOP_END_MS = "home_loop_end_ms";
    public static final String KEY_HOME_CROP_SCALE = "home_crop_scale";
    public static final String KEY_HOME_CROP_OFFSET_X = "home_crop_offset_x";
    public static final String KEY_HOME_CROP_OFFSET_Y = "home_crop_offset_y";

    // Screen wake & playback behaviors
    public static final String KEY_PLAY_ON_WAKE = "play_on_wake";
    public static final String KEY_MUTED = "is_muted";
    public static final String KEY_PLAYBACK_SPEED = "playback_speed";

    // Default values requested by user
    public static final long DEFAULT_LOCK_LOOP_START_MS = 2700L;   // 2.7s
    public static final long DEFAULT_LOCK_LOOP_END_MS = 19000L;    // 19.0s
    public static final long DEFAULT_HOME_LOOP_START_MS = 6000L;   // 6.0s
    public static final long DEFAULT_HOME_LOOP_END_MS = 15900L;    // 15.9s

    public static final long DEFAULT_VIDEO_DURATION_MS = 23100L;

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
