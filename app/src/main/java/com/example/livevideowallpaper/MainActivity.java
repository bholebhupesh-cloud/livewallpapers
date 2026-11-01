package com.example.livevideowallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

/** Small launcher screen that opens the system live-wallpaper preview for this app. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button setButton = findViewById(R.id.set_button);
        setButton.setOnClickListener(v -> openWallpaperPreview());
    }

    private void openWallpaperPreview() {
        Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        preview.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, VideoWallpaperService.class));
        try {
            startActivity(preview);
        } catch (ActivityNotFoundException e) {
            // Some launchers do not support the direct preview; fall back to the chooser.
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }
}
