package com.nissshh.campaper;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class VideoGLRenderer {
    private static final String TAG = "VideoGLRenderer";

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform int uEffectType;\n" + // 0=None, 1=Vibrant, 2=Monochrome, 3=Cyberpunk, 4=Warm Vintage, 5=Cool Vapor
            "uniform float uBrightness;\n" +
            "uniform float uContrast;\n" +
            "uniform float uSaturation;\n" +
            "uniform float uVignette;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(sTexture, vTextureCoord);\n" +
            "  // Brightness\n" +
            "  vec3 rgb = color.rgb + vec3(uBrightness);\n" +
            "  // Contrast\n" +
            "  rgb = (rgb - 0.5) * uContrast + 0.5;\n" +
            "  // Saturation\n" +
            "  float gray = dot(rgb, vec3(0.299, 0.587, 0.114));\n" +
            "  rgb = mix(vec3(gray), rgb, uSaturation);\n" +
            "  // Color Preset Grading\n" +
            "  if (uEffectType == 1) {\n" + // Vibrant Cinema
            "    rgb.r = pow(rgb.r, 0.9) * 1.05;\n" +
            "    rgb.g = pow(rgb.g, 0.95);\n" +
            "    rgb.b = pow(rgb.b, 1.05);\n" +
            "  } else if (uEffectType == 2) {\n" + // Noir B&W High-Contrast
            "    rgb = vec3(pow(gray, 1.15));\n" +
            "  } else if (uEffectType == 3) {\n" + // Cyberpunk / Neon
            "    rgb.r = mix(rgb.r, 1.0 - rgb.g, 0.25);\n" +
            "    rgb.b = rgb.b * 1.25;\n" +
            "  } else if (uEffectType == 4) {\n" + // Warm Vintage / Golden Hour
            "    rgb.r = rgb.r * 1.12;\n" +
            "    rgb.g = rgb.g * 1.03;\n" +
            "    rgb.b = rgb.b * 0.88;\n" +
            "  } else if (uEffectType == 5) {\n" + // Cool Vapor / Moody
            "    rgb.r = rgb.r * 0.9;\n" +
            "    rgb.b = rgb.b * 1.18;\n" +
            "  }\n" +
            "  // Vignette effect\n" +
            "  if (uVignette > 0.01) {\n" +
            "    vec2 uv = vTextureCoord - vec2(0.5);\n" +
            "    float dist = length(uv);\n" +
            "    float vig = smoothstep(0.75, 0.75 - uVignette * 0.45, dist);\n" +
            "    rgb = rgb * vig;\n" +
            "  }\n" +
            "  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), color.a);\n" +
            "}\n";

    private static final float[] TRIANGLE_VERTICES_DATA = {
            -1.0f, -1.0f, 0, 0.0f, 0.0f,
             1.0f, -1.0f, 0, 1.0f, 0.0f,
            -1.0f,  1.0f, 0, 0.0f, 1.0f,
             1.0f,  1.0f, 0, 1.0f, 1.0f,
    };

    private FloatBuffer triangleVertices;
    private final float[] mvpMatrix = new float[16];
    private final float[] stMatrix = new float[16];

    private int program;
    private int textureId;
    private int uMVPMatrixHandle;
    private int uSTMatrixHandle;
    private int aPositionHandle;
    private int aTextureHandle;

    private int uEffectTypeHandle;
    private int uBrightnessHandle;
    private int uContrastHandle;
    private int uSaturationHandle;
    private int uVignetteHandle;

    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private boolean updateSurface = false;

    private int screenWidth = 1440;
    private int screenHeight = 3120;
    private int videoWidth = 2560;
    private int videoHeight = 1440;

    private float cropScale = 1.0f;
    private float cropOffsetX = 0.0f;
    private float cropOffsetY = 0.0f;

    // Effects
    private int effectType = 0;
    private float brightness = 0.0f;
    private float contrast = 1.0f;
    private float saturation = 1.0f;
    private float vignette = 0.0f;

    public VideoGLRenderer() {
        triangleVertices = ByteBuffer.allocateDirect(TRIANGLE_VERTICES_DATA.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        triangleVertices.put(TRIANGLE_VERTICES_DATA).position(0);
        Matrix.setIdentityM(stMatrix, 0);
    }

    public Surface initGL() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");

        uEffectTypeHandle = GLES20.glGetUniformLocation(program, "uEffectType");
        uBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness");
        uContrastHandle = GLES20.glGetUniformLocation(program, "uContrast");
        uSaturationHandle = GLES20.glGetUniformLocation(program, "uSaturation");
        uVignetteHandle = GLES20.glGetUniformLocation(program, "uVignette");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (this) {
                updateSurface = true;
            }
        });

        surface = new Surface(surfaceTexture);
        return surface;
    }

    public void setScreenDimensions(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    public void setVideoDimensions(int width, int height) {
        if (width > 0 && height > 0) {
            this.videoWidth = width;
            this.videoHeight = height;
        }
    }

    public void setCropParameters(float scale, float offsetX, float offsetY) {
        this.cropScale = Math.max(1.0f, scale);
        this.cropOffsetX = offsetX;
        this.cropOffsetY = offsetY;
    }

    public void setEffectParameters(int effectType, float brightness, float contrast, float saturation, float vignette) {
        this.effectType = effectType;
        this.brightness = brightness;
        this.contrast = contrast;
        this.saturation = saturation;
        this.vignette = vignette;
    }

    public void drawFrame() {
        synchronized (this) {
            if (updateSurface) {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
                updateSurface = false;
            }
        }

        GLES20.glViewport(0, 0, screenWidth, screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);

        // Aspect-ratio preserved Cover mode
        Matrix.setIdentityM(mvpMatrix, 0);

        if (screenWidth > 0 && screenHeight > 0 && videoWidth > 0 && videoHeight > 0) {
            float screenRatio = (float) screenWidth / (float) screenHeight;
            float videoRatio = (float) videoWidth / (float) videoHeight;

            float sx = 1.0f;
            float sy = 1.0f;

            if (videoRatio > screenRatio) {
                sx = videoRatio / screenRatio;
                sy = 1.0f;
            } else {
                sx = 1.0f;
                sy = screenRatio / videoRatio;
            }

            sx *= cropScale;
            sy *= cropScale;

            float maxShiftX = Math.max(0.0f, sx - 1.0f);
            float tx = cropOffsetX * maxShiftX;
            float ty = cropOffsetY * Math.max(0.0f, sy - 1.0f);

            Matrix.translateM(mvpMatrix, 0, tx, ty, 0.0f);
            Matrix.scaleM(mvpMatrix, 0, sx, sy, 1.0f);
        }

        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0);

        // Set effect uniforms
        GLES20.glUniform1i(uEffectTypeHandle, effectType);
        GLES20.glUniform1f(uBrightnessHandle, brightness);
        GLES20.glUniform1f(uContrastHandle, contrast);
        GLES20.glUniform1f(uSaturationHandle, saturation);
        GLES20.glUniform1f(uVignetteHandle, vignette);

        triangleVertices.position(0);
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices);
        GLES20.glEnableVertexAttribArray(aPositionHandle);

        triangleVertices.position(3);
        GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices);
        GLES20.glEnableVertexAttribArray(aTextureHandle);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    public void release() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ": " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) return 0;
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) return 0;

        int prog = GLES20.glCreateProgram();
        if (prog != 0) {
            GLES20.glAttachShader(prog, vertexShader);
            GLES20.glAttachShader(prog, pixelShader);
            GLES20.glLinkProgram(prog);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(prog));
                GLES20.glDeleteProgram(prog);
                prog = 0;
            }
        }
        return prog;
    }
}
