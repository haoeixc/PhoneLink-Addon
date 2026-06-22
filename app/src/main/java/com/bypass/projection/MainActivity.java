package com.bypass.projection;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.DataOutputStream;

public class MainActivity extends AppCompatActivity {

    public static boolean isModuleActive() {
        return false;
    }

    private static final String PREFS_NAME = "module_prefs";

    private SharedPreferences prefs;
    private MaterialSwitch masterSwitch;
    private MaterialSwitch switchBypassDialog;
    private MaterialSwitch switchLockscreen;
    private MaterialSwitch switchInvisibleFingerprint;
    private MaterialSwitch switchBlackScreen;
    private MaterialSwitch switchAutoRestore;
    private Button btnRestart;

    @SuppressLint("WorldReadableFiles")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fallback for cached home screen shortcuts pointing directly to MainActivity
        // This is done BEFORE setContentView to prevent unnecessary UI inflation
        java.io.File setupFlag = new java.io.File(getFilesDir(), "setup_complete.flag");
        if (!setupFlag.exists()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Use MODE_WORLD_READABLE so XSharedPreferences can read it
        try {
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        // Find views
        masterSwitch = findViewById(R.id.switchMaster);
        switchBypassDialog = findViewById(R.id.switchBypassDialog);
        switchLockscreen = findViewById(R.id.switchLockscreen);
        switchInvisibleFingerprint = findViewById(R.id.switchInvisibleFingerprint);
        switchBlackScreen = findViewById(R.id.switchBlackScreen);
        switchAutoRestore = findViewById(R.id.switchAutoRestore);
        btnRestart = findViewById(R.id.btnRestart);

        // Load saved states (default all ON)
        masterSwitch.setChecked(prefs.getBoolean("master_switch", true));
        switchBypassDialog.setChecked(prefs.getBoolean("bypass_dialog", true));
        switchLockscreen.setChecked(prefs.getBoolean("lockscreen_bypass", true));
        switchInvisibleFingerprint.setChecked(prefs.getBoolean("invisible_fingerprint", true));
        switchBlackScreen.setChecked(prefs.getBoolean("black_screen_fix", true));
        switchAutoRestore.setChecked(prefs.getBoolean("auto_restore", true));

        // Update UI state
        updateMasterState(masterSwitch.isChecked());

        // Master switch listener
        masterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("master_switch", isChecked).apply();
            updateMasterState(isChecked);
        });

        // Individual toggle listeners
        setupToggle(switchBypassDialog, "bypass_dialog");
        setupToggle(switchLockscreen, "lockscreen_bypass");
        setupToggle(switchInvisibleFingerprint, "invisible_fingerprint");
        setupToggle(switchBlackScreen, "black_screen_fix");
        setupToggle(switchAutoRestore, "auto_restore");

        // Restart button
        btnRestart.setOnClickListener(v -> {
            restartServices();
        });
    }

    // Removed showStartupDialog() as it is now handled by SetupActivity

    private void setupToggle(CompoundButton sw, String key) {
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
        });
    }

    private void updateMasterState(boolean enabled) {
        switchBypassDialog.setEnabled(enabled);
        switchLockscreen.setEnabled(enabled);
        switchInvisibleFingerprint.setEnabled(enabled);
        switchBlackScreen.setEnabled(enabled);
        switchAutoRestore.setEnabled(enabled);
        btnRestart.setEnabled(enabled);

        float alpha = enabled ? 1.0f : 0.4f;
        switchBypassDialog.setAlpha(alpha);
        switchLockscreen.setAlpha(alpha);
        switchInvisibleFingerprint.setAlpha(alpha);
        switchBlackScreen.setAlpha(alpha);
        switchAutoRestore.setAlpha(alpha);
        btnRestart.setAlpha(alpha);
    }

    private void restartServices() {
        btnRestart.setEnabled(false);
        btnRestart.setText("Restarting...");

        new Thread(() -> {
            try {
                // Use su to force-stop all hooked services
                Process su = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(su.getOutputStream());

                // Force-stop Microsoft services
                os.writeBytes("am force-stop com.microsoft.deviceintegrationservice\n");
                os.writeBytes("am force-stop com.microsoft.appmanager\n");
                os.writeBytes("am force-stop com.microsoftsdk.crossdeviceservicebroker\n");

                // Kill and restart SystemUI (it auto-restarts)
                os.writeBytes("killall com.android.systemui\n");

                // Wait a moment, then restart Phone Link
                os.writeBytes("sleep 2\n");
                os.writeBytes("am start -n com.microsoft.appmanager/com.microsoft.appmanager.activities.AppManagerActivity\n");

                os.writeBytes("exit\n");
                os.flush();
                su.waitFor();

                runOnUiThread(() -> {
                    btnRestart.setEnabled(true);
                    btnRestart.setText("Restart Hooked Services");
                    Toast.makeText(this, "All services restarted successfully!", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnRestart.setEnabled(true);
                    btnRestart.setText("Restart Hooked Services");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Root Required")
                            .setMessage("Failed to restart services. Root access was denied or is not available. Please grant root permission to use this feature.")
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }).start();
    }
}
