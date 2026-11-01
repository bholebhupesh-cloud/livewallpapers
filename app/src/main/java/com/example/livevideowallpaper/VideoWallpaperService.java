package com.example.livevideowallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;

/**
 * Live wallpaper that plays the bundled video exactly once each time the screen
 * wakes (power button / lock screen). The video is not looped: when it ends, the
 * last frame stays on screen until the screen turns off again.
 */
public class VideoWallpaperService extends WallpaperService {

    private static final String TAG = "VideoWallpaper";

    // Dark navy taken from the video's opening frame, so the wake-up flash blends in.
    private static final int BACKGROUND_COLOR = Color.rgb(8, 22, 38);

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private class VideoEngine extends Engine {

        private MediaPlayer player;
        private boolean surfaceReady;
        private boolean playedThisWake;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    playOnce();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    resetForNextWake();
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
        }

        @Override
        public void onDestroy() {
            unregisterReceiver(screenReceiver);
            releasePlayer();
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            playOnce();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            playedThisWake = false;
            releasePlayer();
            super.onSurfaceDestroyed(holder);
        }

        /** Plays the video from the start, unless it already played for this wake-up. */
        private void playOnce() {
            if (!surfaceReady || playedThisWake) {
                return;
            }
            playedThisWake = true;
            releasePlayer();
            paintStill(false); // clear the previous frame so the old video does not linger

            try {
                MediaPlayer mp = new MediaPlayer();
                player = mp;

                AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.wallpaper_video);
                try {
                    mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                } finally {
                    afd.close();
                }

                mp.setLooping(false);                 // play once, never repeat
                mp.setVolume(0f, 0f);                 // wallpapers stay silent
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                mp.setSurface(getSurfaceHolder().getSurface());
                mp.setOnPreparedListener(prepared -> prepared.start());
                mp.setOnCompletionListener(done -> Log.d(TAG, "Playback finished, holding last frame"));
                mp.setOnErrorListener((failed, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                    mainHandler.post(() -> {
                        if (player == failed) {
                            releasePlayer();
                            paintStill(true);
                        }
                    });
                    return true;
                });
                mp.prepareAsync();
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not start video", e);
                releasePlayer();
                paintStill(true);
            }
        }

        private void resetForNextWake() {
            playedThisWake = false;
            releasePlayer();
        }

        private void releasePlayer() {
            if (player != null) {
                player.release();
                player = null;
            }
        }

        /** Paints the background colour, optionally with the still image (center-cropped). */
        private void paintStill(boolean withImage) {
            if (!surfaceReady) {
                return;
            }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = holder.lockCanvas();
            if (canvas == null) {
                return;
            }
            try {
                canvas.drawColor(BACKGROUND_COLOR);
                if (withImage) {
                    Bitmap still = BitmapFactory.decodeResource(getResources(), R.drawable.wallpaper_image);
                    if (still != null) {
                        drawCenterCrop(canvas, still);
                        still.recycle();
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
        }

        private void drawCenterCrop(Canvas canvas, Bitmap bitmap) {
            float scale = Math.max(canvas.getWidth() / (float) bitmap.getWidth(),
                    canvas.getHeight() / (float) bitmap.getHeight());
            float dx = (canvas.getWidth() - bitmap.getWidth() * scale) / 2f;
            float dy = (canvas.getHeight() - bitmap.getHeight() * scale) / 2f;
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);
            canvas.drawBitmap(bitmap, matrix, paint);
        }
    }
}
