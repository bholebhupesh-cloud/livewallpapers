package com.example.livevideowallpaper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.RawResourceDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Live wallpaper with a still image on the lock screen. The video plays once
 * after the phone is unlocked, or straight away if there is no lock screen.
 * It is not looped: it stays on its last frame until the screen turns off.
 *
 * The video is drawn through an OpenGL texture (see VideoRenderer), which is the
 * approach that works on phones where decoding straight to the wallpaper fails.
 */
public class VideoWallpaperService extends WallpaperService {

    private static final String TAG = "VideoWallpaper";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 700;

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private class VideoEngine extends Engine implements VideoRenderer.Listener {

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private WallpaperGLSurfaceView glView;
        private VideoRenderer renderer;
        private ExoPlayer player;
        private Surface videoSurface;
        private boolean playedThisWake;
        private int attempts;

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // With no lock screen, play straight away; otherwise wait for unlock.
                    if (!isKeyguardLocked()) {
                        requestPlay();
                    }
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    requestPlay();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    playedThisWake = false;
                    attempts = 0;
                    releasePlayer();
                    renderer.setVideoActive(false);
                }
            }
        };

        /** GLSurfaceView whose SurfaceHolder is the wallpaper's own holder. */
        private class WallpaperGLSurfaceView extends GLSurfaceView {
            WallpaperGLSurfaceView(Context context) {
                super(context);
            }

            @Override
            public SurfaceHolder getHolder() {
                return getSurfaceHolder();
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            glView = new WallpaperGLSurfaceView(VideoWallpaperService.this);
            glView.setEGLContextClientVersion(2);
            glView.setPreserveEGLContextOnPause(true);
            renderer = new VideoRenderer(glView, VideoWallpaperService.this, this);
            glView.setRenderer(renderer);
            glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
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
        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                glView.onResume();
            } else {
                glView.onPause();
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            glView.surfaceCreated(holder);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            glView.surfaceChanged(holder, format, width, height);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            glView.surfaceDestroyed(holder);
            releasePlayer();
            playedThisWake = false;
            super.onSurfaceDestroyed(holder);
        }

        // Called on the GL thread by VideoRenderer; hop to the main thread for the player.
        @Override
        public void onVideoSurfaceReady(Surface surface) {
            mainHandler.post(() -> {
                videoSurface = surface;
                releasePlayer();
                startIfReady();
            });
        }

        @Override
        public void onVideoSurfaceLost() {
            mainHandler.post(() -> {
                videoSurface = null;
                releasePlayer();
                renderer.setVideoActive(false);
            });
        }

        /** Asks for one play-through for this wake or unlock; ignored if one already started. */
        private void requestPlay() {
            if (playedThisWake) {
                return;
            }
            playedThisWake = true;
            attempts = 0;
            startIfReady();
        }

        private void startIfReady() {
            if (!playedThisWake || videoSurface == null || player != null) {
                return;
            }
            startPlayer();
        }

        private void startPlayer() {
            attempts++;
            Log.d(TAG, "Starting video, attempt " + attempts);
            try {
                DefaultRenderersFactory renderers = new DefaultRenderersFactory(VideoWallpaperService.this)
                        .setEnableDecoderFallback(true); // software decoder if hardware fails
                ExoPlayer exo = new ExoPlayer.Builder(VideoWallpaperService.this, renderers).build();
                player = exo;

                exo.setVideoSurface(videoSurface);
                exo.setVolume(0f);                         // wallpapers stay silent
                exo.setRepeatMode(Player.REPEAT_MODE_OFF); // play once, never repeat

                exo.addListener(new Player.Listener() {
                    @Override
                    public void onRenderedFirstFrame() {
                        renderer.setVideoActive(true);
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        Log.e(TAG, "Playback error on attempt " + attempts, error);
                        mainHandler.post(() -> {
                            if (player != exo) {
                                return;
                            }
                            releasePlayer();
                            renderer.setVideoActive(false);
                            if (attempts < MAX_ATTEMPTS) {
                                mainHandler.postDelayed(() -> {
                                    if (playedThisWake && videoSurface != null) {
                                        startIfReady();
                                    }
                                }, RETRY_DELAY_MS);
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
                releasePlayer();
            }
        }

        private boolean isKeyguardLocked() {
            KeyguardManager keyguard =
                    (KeyguardManager) VideoWallpaperService.this.getSystemService(Context.KEYGUARD_SERVICE);
            return keyguard != null && keyguard.isKeyguardLocked();
        }

        private void releasePlayer() {
            if (player != null) {
                player.release();
                player = null;
            }
        }
    }
}
