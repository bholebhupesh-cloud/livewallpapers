package com.example.timeofdaywallpaper;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.Calendar;

/**
 * Colour wallpaper whose gradient follows the time of day, similar to Samsung's
 * dynamic wallpapers: dawn, day, sunset and night colours blend smoothly.
 * It is drawn directly on the wallpaper surface, so no video is needed.
 */
public class TimeOfDayWallpaperService extends WallpaperService {

    private static final long UPDATE_INTERVAL_MS = 60_000L;

    // Times in hours (0-24) and the top and bottom colour of the gradient at that time.
    // The last entry repeats midnight so the evening blends back into the night.
    private static final float[] HOURS = {0f, 5f, 7.5f, 12f, 17f, 19.5f, 21.5f, 24f};
    private static final int[] TOP = {
            0xFF0A0F24, // midnight
            0xFF2B2D62, // pre-dawn
            0xFF7CB8F0, // morning
            0xFF3D8EF2, // midday
            0xFF4A7BD0, // late afternoon
            0xFF5B3F9A, // sunset
            0xFF141B45, // night
            0xFF0A0F24, // midnight again
    };
    private static final int[] BOTTOM = {
            0xFF1A2350,
            0xFFE08A8A,
            0xFFF7D9C4,
            0xFFBFE3FF,
            0xFFF6B26B,
            0xFFF2739A,
            0xFF2B2F6E,
            0xFF1A2350,
    };

    @Override
    public Engine onCreateEngine() {
        return new GradientEngine();
    }

    private class GradientEngine extends Engine {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint();
        private final Runnable refresh = this::draw;
        private boolean visible;

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                draw();
            } else {
                handler.removeCallbacks(refresh);
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            draw();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            draw();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            handler.removeCallbacks(refresh);
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacks(refresh);
            super.onDestroy();
        }

        /** Paints the gradient for the current time, then schedules the next refresh. */
        private void draw() {
            handler.removeCallbacks(refresh);
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = holder.lockCanvas();
            if (canvas != null) {
                try {
                    paintGradient(canvas);
                } finally {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
            if (visible) {
                handler.postDelayed(refresh, UPDATE_INTERVAL_MS);
            }
        }

        private void paintGradient(Canvas canvas) {
            float hour = currentHour();
            int top = colourAt(TOP, hour);
            int bottom = colourAt(BOTTOM, hour);
            paint.setShader(new LinearGradient(
                    0, 0, 0, canvas.getHeight(), top, bottom, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), paint);
        }
    }

    /** Local time of day as hours from 0 to 24, including minutes and seconds. */
    static float currentHour() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY)
                + now.get(Calendar.MINUTE) / 60f
                + now.get(Calendar.SECOND) / 3600f;
    }

    /** Blends between the palette entries surrounding the given hour. */
    static int colourAt(int[] palette, float hour) {
        for (int i = 0; i < HOURS.length - 1; i++) {
            if (hour >= HOURS[i] && hour <= HOURS[i + 1]) {
                float span = HOURS[i + 1] - HOURS[i];
                float fraction = span == 0f ? 0f : (hour - HOURS[i]) / span;
                return blend(palette[i], palette[i + 1], fraction);
            }
        }
        return palette[0];
    }

    /** Linear blend of two ARGB colours. */
    static int blend(int from, int to, float fraction) {
        int a = mix((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, fraction);
        int r = mix((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, fraction);
        int g = mix((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, fraction);
        int b = mix(from & 0xFF, to & 0xFF, fraction);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int from, int to, float fraction) {
        return Math.round(from + (to - from) * fraction);
    }
}
