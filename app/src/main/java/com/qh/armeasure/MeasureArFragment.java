package com.qh.armeasure;

import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.sceneform.ux.ArFragment;

/**
 * ARCore setup for measurement.
 *
 * Plane/depth hits are preferred for committed points. Instant Placement stays enabled as a
 * fallback, but points that have only an approximate screen-space distance are marked as estimates.
 */
public class MeasureArFragment extends ArFragment {

    private boolean depthEnabled;

    @Override
    protected Config getSessionConfiguration(Session session) {
        Config config = new Config(session);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP);
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
        config.setFocusMode(Config.FocusMode.AUTO);

        // Use ARCore's motion-assisted depth where this device supports it (useful around 2 m).
        // Do not assume support based on the phone model; ARCore checks the actual camera/device.
        depthEnabled = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
        config.setDepthMode(depthEnabled
                ? Config.DepthMode.AUTOMATIC
                : Config.DepthMode.DISABLED);
        return config;
    }

    public boolean isDepthEnabled() {
        return depthEnabled;
    }
}
