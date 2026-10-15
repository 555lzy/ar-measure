package com.qh.armeasure;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

/** Entry screen: request camera permission, then check/install ARCore before opening the measure view. */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1;
    private static final long AVAILABILITY_POLL_MS = 350L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean arCoreInstallRequested;
    private boolean checkingAvailability;

    private final Runnable availabilityPoll = () -> {
        if (checkingAvailability && !isFinishing() && !isDestroyed()) {
            checkArCoreAndStart();
        }
    };

    private TextView statusText;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.txt_status);
        startButton = findViewById(R.id.btn_start);
        startButton.setOnClickListener(v -> tryStartMeasure());
        refreshUi();
    }

    private void refreshUi() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        // Keep the button enabled without permission: tapping it is how the user grants permission.
        startButton.setEnabled(!checkingAvailability);
        statusText.setText(cameraGranted
                ? getString(R.string.msg_ready)
                : getString(R.string.msg_need_camera));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            refreshUi();
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkArCoreAndStart();
            }
        }
    }

    private void tryStartMeasure() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        checkArCoreAndStart();
    }

    /** ARCore availability can initially be UNKNOWN_CHECKING; poll instead of falsely rejecting it. */
    private void checkArCoreAndStart() {
        try {
            ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
            if (availability.isTransient()) {
                checkingAvailability = true;
                statusText.setText(R.string.msg_checking_arcore);
                startButton.setEnabled(false);
                mainHandler.removeCallbacks(availabilityPoll);
                mainHandler.postDelayed(availabilityPoll, AVAILABILITY_POLL_MS);
                return;
            }

            checkingAvailability = false;
            mainHandler.removeCallbacks(availabilityPoll);
            startButton.setEnabled(true);
            if (!availability.isSupported()) {
                statusText.setText(R.string.msg_no_arcore);
                return;
            }

            // ARCore must be installed/upgraded before creating a Session.
            ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance()
                    .requestInstall(this, !arCoreInstallRequested);
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                arCoreInstallRequested = true;
                return;
            }
            startActivity(new Intent(this, ArMeasureActivity.class));
        } catch (UnavailableUserDeclinedInstallationException e) {
            statusText.setText(R.string.msg_declined_install);
        } catch (UnavailableException e) {
            statusText.setText(getString(
                    R.string.msg_ar_unavailable, e.getClass().getSimpleName()));
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(availabilityPoll);
        super.onDestroy();
    }
}
