package com.example.livevideowallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws either the still image or the video. The video is decoded into an OpenGL
 * texture (SurfaceTexture) instead of straight onto the wallpaper surface, which
 * is what fails with DECODER_INIT_FAILED on some phones.
 */
class VideoRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "VideoRenderer";

    /** Aspect ratio (width / height) of the bundled video, 576 x 1280. */
    private static final float VIDEO_ASPECT = 576f / 1280f;

    interface Listener {
        /** Called on the GL thread when a new Surface for the video is ready. */
        void onVideoSurfaceReady(Surface surface);

        /** Called on the GL thread when the video Surface has gone away. */
        void onVideoSurfaceLost();
    }

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
            + "attribute vec2 aTexCoord;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "uniform vec2 uScale;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);\n"
            + "  vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n"
            + "}\n";

    private static final String VIDEO_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
            + "}\n";

    private static final String IMAGE_FRAGMENT_SHADER =
            "precision mediump float;\n"
            + "uniform sampler2D sTexture;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
            + "}\n";

    // Full-screen quad as a triangle strip. Each vertex is x, y, u, v.
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
    };

    // Flips the image vertically, because bitmap rows start at the top.
    private static final float[] FLIP_Y = {
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
    };

    private final GLSurfaceView view;
    private final Context context;
    private final Listener listener;
    private final FloatBuffer quadBuffer;
    private final float[] texMatrix = new float[16];

    private Program videoProgram;
    private Program imageProgram;
    private int videoTextureId;
    private int imageTextureId;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private float imageAspect = 0.45f;
    private float screenAspect = 0.45f;
    private volatile boolean frameAvailable;
    private volatile boolean videoActive;

    VideoRenderer(GLSurfaceView view, Context context, Listener listener) {
        this.view = view;
        this.context = context;
        this.listener = listener;
        ByteBuffer bytes = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder());
        quadBuffer = bytes.asFloatBuffer();
        quadBuffer.put(QUAD);
        quadBuffer.position(0);
    }

    /** Switches between the video texture (true) and the still image (false). */
    void setVideoActive(boolean active) {
        videoActive = active;
        view.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Clean up anything left over from a previous GL surface.
        listener.onVideoSurfaceLost();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }

        videoProgram = new Program(VERTEX_SHADER, VIDEO_FRAGMENT_SHADER);
        imageProgram = new Program(VERTEX_SHADER, IMAGE_FRAGMENT_SHADER);

        int[] ids = new int[2];
        GLES20.glGenTextures(2, ids, 0);
        videoTextureId = ids[0];
        imageTextureId = ids[1];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        setTextureParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        surfaceTexture = new SurfaceTexture(videoTextureId);
        surfaceTexture.setOnFrameAvailableListener(this);
        surface = new Surface(surfaceTexture);
        listener.onVideoSurfaceReady(surface);

        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.wallpaper_image);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId);
        setTextureParams(GLES20.GL_TEXTURE_2D);
        if (bitmap != null) {
            imageAspect = bitmap.getWidth() / (float) bitmap.getHeight();
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            bitmap.recycle();
        }

        frameAvailable = false;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        screenAspect = width / (float) height;
        view.requestRender();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClearColor(8f / 255f, 22f / 255f, 38f / 255f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (videoActive && surfaceTexture != null) {
            if (frameAvailable) {
                frameAvailable = false;
                surfaceTexture.updateTexImage();
            }
            surfaceTexture.getTransformMatrix(texMatrix);
            drawQuad(videoProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId, texMatrix, VIDEO_ASPECT);
        } else {
            drawQuad(imageProgram, GLES20.GL_TEXTURE_2D, imageTextureId, FLIP_Y, imageAspect);
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture texture) {
        frameAvailable = true;
        view.requestRender();
    }

    /** Draws the quad, cropped to fill the screen (centre crop, like a wallpaper should). */
    private void drawQuad(Program program, int target, int textureId, float[] matrix, float contentAspect) {
        float scaleX;
        float scaleY;
        if (screenAspect > contentAspect) {
            scaleX = 1f;
            scaleY = screenAspect / contentAspect;
        } else {
            scaleX = contentAspect / screenAspect;
            scaleY = 1f;
        }

        GLES20.glUseProgram(program.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(target, textureId);
        GLES20.glUniform1i(program.uSampler, 0);
        GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, matrix, 0);
        GLES20.glUniform2f(program.uScale, scaleX, scaleY);

        quadBuffer.position(0);
        GLES20.glVertexAttribPointer(program.aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
        quadBuffer.position(2);
        GLES20.glVertexAttribPointer(program.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
        GLES20.glEnableVertexAttribArray(program.aPosition);
        GLES20.glEnableVertexAttribArray(program.aTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(program.aPosition);
        GLES20.glDisableVertexAttribArray(program.aTexCoord);
    }

    private static void setTextureParams(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    /** A linked shader program and the locations of its variables. */
    private static final class Program {
        final int id;
        final int aPosition;
        final int aTexCoord;
        final int uTexMatrix;
        final int uScale;
        final int uSampler;

        Program(String vertexSource, String fragmentSource) {
            int vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            id = GLES20.glCreateProgram();
            GLES20.glAttachShader(id, vertex);
            GLES20.glAttachShader(id, fragment);
            GLES20.glLinkProgram(id);
            int[] status = new int[1];
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(id));
            }
            aPosition = GLES20.glGetAttribLocation(id, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord");
            uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix");
            uScale = GLES20.glGetUniformLocation(id, "uScale");
            uSampler = GLES20.glGetUniformLocation(id, "sTexture");
        }
    }
}
