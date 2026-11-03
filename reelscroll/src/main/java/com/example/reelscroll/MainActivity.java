package com.example.reelscroll;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

/** Shows whether the accessibility service is on and opens the settings screen to turn it on. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.btn_open_settings).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView status = findViewById(R.id.tv_status);
        if (isServiceOn()) {
            status.setText("Reel Auto Scroll is ON. Open reels in Instagram, Facebook or YouTube, "
                    + "then use the red floating button to change settings.");
        } else {
            status.setText("Reel Auto Scroll is OFF. Open accessibility settings and turn on "
                    + "\"Reel Auto Scroll\".");
        }
    }

    private boolean isServiceOn() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null
                && enabled.contains(getPackageName() + "/" + ReelScrollService.class.getName());
    }
}
