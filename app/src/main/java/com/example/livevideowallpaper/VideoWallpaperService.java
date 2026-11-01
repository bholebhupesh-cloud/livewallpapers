package com.example.livevideowallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.RawResourceDataSource;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Live wallpaper that plays the bundled video exactly once each time the screen
 * wakes (power button / lock screen). The video is not looped: when it ends, the
 * last frame stays on screen until the screen turns off again.
 *
 * If the video cannot play, the still image is shown instead together with a
 * status line describing the problem, so the cause is visible on the phone.
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

        private ExoPlayer player;
        private boolean surfaceReady;
        private boolean playedThisWake;
        private String status = "waiting for screen on";
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    setStatus("screen on");
                    playOnce();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    resetForNextWake();
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(32f);
            textPaint.setShadowLayer(6f, 0f, 0f, Color.BLACK);

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
            setStatus("surface ready");
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
                setStatus("starting player");
                ExoPlayer exo = new ExoPlayer.Builder(VideoWallpaperService.this).build();
                player = exo;

                exo.setVideoSurface(getSurfaceHolder().getSurface());
                exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                exo.setVolume(0f);                              // wallpapers stay silent
                exo.setRepeatMode(Player.REPEAT_MODE_OFF);      // play once, never repeat

                exo.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) {
                            setStatus("playing");
                        } else if (state == Player.STATE_ENDED) {
                            setStatus("played once");
                        }
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        Log.e(TAG, "Playback error", error);
                        setStatus("error: " + error.getErrorCodeName());
                        mainHandler.post(() -> {
                            if (player == exo) {
                                releasePlayer();
                                paintStill(true);
                            }
                        });
                    }
                });

                exo.setMediaItem(MediaItem.fromUri(
                        RawResourceDataSource.buildRawResourceUri(R.raw.wallpaper_video)));
                exo.prepare();
                exo.play();
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not start player", e);
                setStatus("failed: " + e.getClass().getSimpleName());
                releasePlayer();
                paintStill(true);
            }
        }

        private void setStatus(String message) {
            status = message;
            Log.d(TAG, message);
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

        /** Paints the background colour, optionally with the still image (center-cropped), plus the status line. */
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
                canvas.drawText(status, 40f, canvas.getHeight() - 160f, textPaint);
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
