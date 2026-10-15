package com.qh.armeasure;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.google.ar.core.Anchor;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Material;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.rendering.ViewSizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARCore measuring screen.
 *
 * Precision-first is the default: prefer a tracked physical surface/depth/feature hit and refuse an
 * Instant Placement result while it still has only an estimated screen-space distance. Quick mode
 * is available as an explicit fallback; those approximate endpoints and their results are marked
 * with "≈" instead of being presented as measured facts.
 */
public class ArMeasureActivity extends AppCompatActivity {

    private enum Unit { CENTIMETER, METER, FOOT, INCH }
    private enum MeasureMode { PRECISION, QUICK_ESTIMATE }
    private enum HitQuality { PLANE, DEPTH, FEATURE, INSTANT_FULL, INSTANT_APPROXIMATE }

    private static final float POINT_RADIUS = 0.010f;
    private static final float LINE_THICKNESS = 0.008f;
    private static final long HIT_TEST_INTERVAL_MS = 60L;
    private static final long HUD_INTERVAL_MS = 150L;
    private static final int STABILITY_REQUIRED_SAMPLES = 6;
    private static final long STABILITY_MIN_DURATION_MS = 300L;
    private static final float STABILITY_RADIUS_METERS = 0.025f;
    private static final float INSTANT_FALLBACK_DISTANCE_METERS = 2.0f;
    private static final float LABEL_WIDTH_METERS = 0.16f;
    private static final float LABEL_HEIGHT_METERS = 0.04f;

    private MeasureArFragment arFragment;
    private TextView hudText;
    private TextView qualityText;
    private TextView tipText;
    private Button placeButton;
    private Button modeButton;

    private Unit unit = Unit.CENTIMETER;
    private MeasureMode measureMode = MeasureMode.PRECISION;
    private boolean cameraTracking;
    private String lastQualityMessage;

    /** A classified hit. The classification is also carried into the final result. */
    private static final class HitCandidate {
        final HitResult hit;
        final HitQuality quality;

        HitCandidate(HitResult hit, HitQuality quality) {
            this.hit = hit;
            this.quality = quality;
        }
    }

    /** Measurement endpoint, attached to an ARCore anchor at the moment the user commits it. */
    private static final class MeasurePoint {
        Node marker;
        AnchorNode fixedNode;
        HitQuality quality;
        volatile boolean removed;
    }

    private static final class MeasureSegment {
        MeasurePoint start;
        MeasurePoint end;
        Node lineNode;
        Node labelNode;
        TextView labelView;
    }

    private final List<MeasurePoint> points = new ArrayList<>();
    private final List<MeasureSegment> segments = new ArrayList<>();

    private HitCandidate currentTarget;
    private boolean targetStable;
    private int stableSampleCount;
    private long targetStableSince;
    private Vector3 stableOrigin;
    private HitQuality stableQuality;

    private Node previewLine;
    private Node previewLabel;
    private TextView previewView;
    private Material lineMaterial;
    private Material pointMaterial;

    private long lastHitTestAt;
    private long lastHudUpdateAt;

    private final Runnable hideTipRunnable = () -> {
        if (tipText != null) {
            tipText.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar_measure);

        hudText = findViewById(R.id.txt_hud);
        qualityText = findViewById(R.id.txt_quality);
        tipText = findViewById(R.id.txt_tip);
        placeButton = findViewById(R.id.btn_place);
        modeButton = findViewById(R.id.btn_mode);
        Button undoButton = findViewById(R.id.btn_undo);
        Button clearButton = findViewById(R.id.btn_clear);
        Button unitButton = findViewById(R.id.btn_unit);

        placeButton.setOnClickListener(v -> placeAtScreenCenter());
        modeButton.setOnClickListener(v -> toggleMeasurementMode());
        undoButton.setOnClickListener(v -> undoLast());
        clearButton.setOnClickListener(v -> clearAll());
        unitButton.setOnClickListener(v -> showUnitDialog());
        updateModeButton();
        updatePlaceButton();

        FragmentManager fm = getSupportFragmentManager();
        arFragment = (MeasureArFragment) fm.findFragmentById(R.id.ar_fragment);
        if (arFragment == null) {
            finish();
            return;
        }

        // The reticle and the button both refer to screen center. Do not also accept arbitrary
        // screen taps: that made it unclear where a point would actually be placed.
        arFragment.getArSceneView().getScene().addOnUpdateListener(this::onSceneUpdate);

        // Prepare renderables ahead of time so the first committed point does not stall on creation.
        MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0x3B, 0x30))
                .thenAccept(material -> runOnUiThread(() -> {
                    pointMaterial = material;
                    updatePlaceButton();
                }));
        MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0xC1, 0x07))
                .thenAccept(material -> runOnUiThread(() -> {
                    lineMaterial = material;
                    updatePlaceButton();
                }));
    }

    // ---------------- Target selection and placement ----------------

    private void placeAtScreenCenter() {
        if (arFragment == null) return;
        Frame frame = arFragment.getArSceneView().getArFrame();
        long now = SystemClock.uptimeMillis();
        refreshTarget(frame, now, true);

        if (!cameraTracking) {
            showTip(getString(R.string.tip_locating));
            return;
        }
        if (currentTarget == null) {
            showTip(getString(R.string.tip_no_surface));
            return;
        }
        if (!isPlaceable(currentTarget)) {
            showTip(getString(R.string.tip_estimate_blocked));
            return;
        }
        if (!targetStable) {
            showTip(getString(R.string.tip_stabilizing));
            return;
        }
        if (pointMaterial == null || (!points.isEmpty() && lineMaterial == null)) {
            showTip(getString(R.string.tip_material_loading));
            return;
        }
        placePoint(currentTarget);
    }

    /**
     * Select the nearest usable physical hit first. Instant Placement is only a fallback, and an
     * approximate screen-space hit is kept distinct from a fully tracked hit.
     */
    private static HitCandidate chooseBestHit(List<HitResult> hits) {
        if (hits == null || hits.isEmpty()) return null;

        HitCandidate bestPhysical = null;
        float bestPhysicalDistance = Float.MAX_VALUE;
        HitCandidate bestFullInstant = null;
        float bestFullInstantDistance = Float.MAX_VALUE;
        HitCandidate bestApproxInstant = null;
        float bestApproxInstantDistance = Float.MAX_VALUE;

        for (HitResult hit : hits) {
            if (hit == null) continue;
            Trackable trackable = hit.getTrackable();
            if (trackable == null || trackable.getTrackingState() != TrackingState.TRACKING) continue;

            HitQuality quality = null;
            if (trackable instanceof Plane) {
                if (((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
                    quality = HitQuality.PLANE;
                }
            } else if (trackable instanceof DepthPoint) {
                quality = HitQuality.DEPTH;
            } else if (trackable instanceof Point) {
                quality = HitQuality.FEATURE;
            } else if (trackable instanceof InstantPlacementPoint) {
                InstantPlacementPoint instantPoint = (InstantPlacementPoint) trackable;
                if (instantPoint.getTrackingMethod()
                        == InstantPlacementPoint.TrackingMethod.FULL_TRACKING) {
                    float distance = hit.getDistance();
                    if (distance < bestFullInstantDistance) {
                        bestFullInstantDistance = distance;
                        bestFullInstant = new HitCandidate(hit, HitQuality.INSTANT_FULL);
                    }
                } else {
                    float distance = hit.getDistance();
                    if (distance < bestApproxInstantDistance) {
                        bestApproxInstantDistance = distance;
                        bestApproxInstant = new HitCandidate(
                                hit, HitQuality.INSTANT_APPROXIMATE);
                    }
                }
            }

            if (quality != null) {
                float distance = hit.getDistance();
                if (bestPhysical == null
                        || distance < bestPhysicalDistance
                        || (Math.abs(distance - bestPhysicalDistance) < 0.01f
                        && hitPriority(quality) < hitPriority(bestPhysical.quality))) {
                    bestPhysicalDistance = distance;
                    bestPhysical = new HitCandidate(hit, quality);
                }
            }
        }

        if (bestPhysical != null) return bestPhysical;
        if (bestFullInstant != null) return bestFullInstant;
        return bestApproxInstant;
    }

    private static int hitPriority(HitQuality quality) {
        switch (quality) {
            case PLANE: return 0;
            case DEPTH: return 1;
            case FEATURE: return 2;
            default: return 3;
        }
    }

    private boolean isPlaceable(HitCandidate candidate) {
        if (candidate == null) return false;
        return measureMode == MeasureMode.QUICK_ESTIMATE
                || candidate.quality != HitQuality.INSTANT_APPROXIMATE;
    }

    private void refreshTarget(Frame frame, long now, boolean forceHitTest) {
        cameraTracking = frame != null
                && frame.getCamera().getTrackingState() == TrackingState.TRACKING;
        if (!cameraTracking) {
            currentTarget = null;
            resetTargetStability();
        } else if (forceHitTest || now - lastHitTestAt >= HIT_TEST_INTERVAL_MS) {
            lastHitTestAt = now;
            currentTarget = hitTestAtScreenCenter(frame);
            updateTargetStability(currentTarget, now);
        }
        updateQualityStatus();
        updatePlaceButton();
    }

    /** Require a short, bounded-variance dwell before enabling a precision point. */
    private void updateTargetStability(HitCandidate candidate, long now) {
        if (candidate == null) {
            resetTargetStability();
            return;
        }

        float[] translation = candidate.hit.getHitPose().getTranslation();
        Vector3 position = new Vector3(translation[0], translation[1], translation[2]);
        if (stableOrigin == null
                || isApproximate(stableQuality) != isApproximate(candidate.quality)
                || distance(stableOrigin, position) > STABILITY_RADIUS_METERS) {
            stableOrigin = position;
            stableQuality = candidate.quality;
            stableSampleCount = 1;
            targetStableSince = now;
            targetStable = false;
            return;
        }

        stableSampleCount++;
        targetStable = stableSampleCount >= STABILITY_REQUIRED_SAMPLES
                && now - targetStableSince >= STABILITY_MIN_DURATION_MS;
    }

    private void resetTargetStability() {
        stableOrigin = null;
        stableQuality = null;
        stableSampleCount = 0;
        targetStableSince = 0L;
        targetStable = false;
    }

    private HitCandidate hitTestAtScreenCenter(Frame frame) {
        View arView = arFragment == null ? null : arFragment.getArSceneView();
        if (arView == null || arView.getWidth() <= 0 || arView.getHeight() <= 0) {
            return null;
        }

        float x = arView.getWidth() / 2f;
        float y = arView.getHeight() / 2f;
        HitCandidate physicalHit = chooseBestHit(frame.hitTest(x, y));
        if (physicalHit != null) {
            return physicalHit;
        }

        // Per ARCore's API guidance, only try Instant Placement after normal hit testing finds
        // nothing. Two meters is the expected use distance; results remain explicitly approximate
        // until ARCore reports FULL_TRACKING.
        return chooseBestHit(frame.hitTestInstantPlacement(
                x, y, INSTANT_FALLBACK_DISTANCE_METERS));
    }

    private void updateQualityStatus() {
        if (qualityText == null) return;

        final String message;
        if (!cameraTracking) {
            message = getString(R.string.quality_locating);
        } else if (currentTarget == null) {
            boolean depthEnabled = arFragment != null && arFragment.isDepthEnabled();
            message = getString(depthEnabled
                    ? R.string.quality_no_hit_depth
                    : R.string.quality_no_hit);
        } else if (currentTarget.quality == HitQuality.INSTANT_APPROXIMATE) {
            if (measureMode == MeasureMode.PRECISION) {
                message = getString(R.string.quality_approx_blocked);
            } else {
                message = getString(targetStable
                        ? R.string.quality_approx_allowed
                        : R.string.quality_approx_stabilizing);
            }
        } else {
            String hitName;
            switch (currentTarget.quality) {
                case PLANE:
                    hitName = getString(R.string.quality_plane);
                    break;
                case DEPTH:
                    hitName = getString(R.string.quality_depth);
                    break;
                case FEATURE:
                    hitName = getString(R.string.quality_feature);
                    break;
                case INSTANT_FULL:
                    hitName = getString(R.string.quality_instant_full);
                    break;
                default:
                    hitName = getString(R.string.quality_plane);
                    break;
            }
            message = getString(targetStable
                    ? R.string.quality_ready
                    : R.string.quality_stabilizing, hitName);
        }

        if (!message.equals(lastQualityMessage)) {
            qualityText.setText(message);
            lastQualityMessage = message;
        }
    }

    private void placePoint(HitCandidate candidate) {
        Anchor anchor = null;
        AnchorNode anchorNode = null;
        try {
            anchor = candidate.hit.createAnchor();
            MeasurePoint point = new MeasurePoint();
            point.quality = candidate.quality;

            anchorNode = new AnchorNode(anchor);
            anchorNode.setParent(arFragment.getArSceneView().getScene());
            point.fixedNode = anchorNode;

            ModelRenderable markerRenderable = ShapeFactory.makeSphere(
                    POINT_RADIUS, Vector3.zero(), pointMaterial);
            Node marker = new Node();
            marker.setParent(anchorNode);
            marker.setLocalPosition(Vector3.zero());
            marker.setRenderable(markerRenderable);
            point.marker = marker;

            if (!points.isEmpty()) {
                addSegment(points.get(points.size() - 1), point);
            }
            points.add(point);
            updateHudNow();

            if (candidate.quality == HitQuality.INSTANT_APPROXIMATE) {
                showTip(getString(R.string.tip_estimate_added));
            }
        } catch (Exception e) {
            if (anchor != null) anchor.detach();
            if (anchorNode != null) anchorNode.setParent(null);
            showTip(getString(R.string.tip_place_failed));
        }
        updatePlaceButton();
    }

    private void addSegment(MeasurePoint start, MeasurePoint end) {
        if (lineMaterial == null) {
            throw new IllegalStateException("Line material is not ready");
        }
        final MeasureSegment segment = new MeasureSegment();
        segment.start = start;
        segment.end = end;

        ModelRenderable lineRenderable = ShapeFactory.makeCube(
                new Vector3(LINE_THICKNESS, LINE_THICKNESS, 1.0f),
                Vector3.zero(), lineMaterial);
        lineRenderable.setShadowReceiver(false);
        lineRenderable.setShadowCaster(false);
        Node lineNode = new Node();
        lineNode.setParent(arFragment.getArSceneView().getScene());
        lineNode.setRenderable(lineRenderable);
        segment.lineNode = lineNode;

        ViewRenderable.builder()
                .setView(this, R.layout.view_distance_label)
                .setSizer((ViewSizer) view -> new Vector3(
                        LABEL_WIDTH_METERS, LABEL_HEIGHT_METERS, 1f))
                .build()
                .thenAccept(labelRenderable -> runOnUiThread(() -> {
                    if (segment.start.removed || segment.end.removed
                            || isFinishing() || isDestroyed() || arFragment == null) {
                        return;
                    }
                    Node labelNode = new Node();
                    labelNode.setParent(arFragment.getArSceneView().getScene());
                    labelNode.setRenderable(labelRenderable);
                    segment.labelNode = labelNode;
                    segment.labelView = (TextView) labelRenderable.getView();
                }));
        segments.add(segment);
    }

    // ---------------- Per-frame AR scene updates ----------------

    private void onSceneUpdate(FrameTime frameTime) {
        if (arFragment == null || isFinishing() || isDestroyed()) return;

        long now = SystemClock.uptimeMillis();
        Frame frame = arFragment.getArSceneView().getArFrame();
        refreshTarget(frame, now, false);
        updateSegments();
        updatePreview(currentTarget);

        if (now - lastHudUpdateAt >= HUD_INTERVAL_MS) {
            lastHudUpdateAt = now;
            updateHudNow();
        }
    }

    private void updateSegments() {
        Vector3 cameraPosition = arFragment.getArSceneView()
                .getScene().getCamera().getWorldPosition();
        for (MeasureSegment segment : segments) {
            if (segment.lineNode == null || segment.start == null || segment.end == null) {
                continue;
            }
            Vector3 start = segment.start.marker.getWorldPosition();
            Vector3 end = segment.end.marker.getWorldPosition();
            Vector3 difference = Vector3.subtract(end, start);
            float length = length(difference);
            if (length < 0.005f) {
                segment.lineNode.setEnabled(false);
                if (segment.labelNode != null) segment.labelNode.setEnabled(false);
                continue;
            }

            Vector3 center = Vector3.add(start, end).scaled(0.5f);
            segment.lineNode.setEnabled(true);
            segment.lineNode.setWorldPosition(center);
            segment.lineNode.setLocalRotation(Quaternion.rotationBetweenVectors(
                    new Vector3(0f, 0f, 1f), difference.normalized()));
            segment.lineNode.setWorldScale(new Vector3(1f, 1f, length));
            boolean approximate = isApproximate(segment.start.quality)
                    || isApproximate(segment.end.quality);
            placeLabel(segment.labelNode, segment.labelView, center, difference,
                    cameraPosition, length, approximate);
        }
    }

    /** Live preview from the last committed point to the current reticle hit. */
    private void updatePreview(HitCandidate candidate) {
        if (points.isEmpty() || candidate == null) {
            setPreviewVisible(false);
            return;
        }

        if (previewLine == null) {
            createPreviewNodes();
            if (previewLine == null) return;
        }

        float[] translation = candidate.hit.getHitPose().getTranslation();
        Vector3 target = new Vector3(translation[0], translation[1], translation[2]);
        MeasurePoint lastPoint = points.get(points.size() - 1);
        Vector3 start = lastPoint.marker.getWorldPosition();
        Vector3 difference = Vector3.subtract(target, start);
        float length = length(difference);
        if (length < 0.005f) {
            setPreviewVisible(false);
            return;
        }

        Vector3 center = Vector3.add(start, target).scaled(0.5f);
        Vector3 cameraPosition = arFragment.getArSceneView()
                .getScene().getCamera().getWorldPosition();
        previewLine.setEnabled(true);
        previewLine.setWorldPosition(center);
        previewLine.setLocalRotation(Quaternion.rotationBetweenVectors(
                new Vector3(0f, 0f, 1f), difference.normalized()));
        previewLine.setWorldScale(new Vector3(1f, 1f, length));
        placeLabel(previewLabel, previewView, center, difference, cameraPosition, length,
                isApproximate(candidate.quality));
    }

    private void createPreviewNodes() {
        if (lineMaterial == null || arFragment == null) return;
        ModelRenderable lineRenderable = ShapeFactory.makeCube(
                new Vector3(LINE_THICKNESS * 0.6f, LINE_THICKNESS * 0.6f, 1.0f),
                Vector3.zero(), lineMaterial);
        lineRenderable.setShadowReceiver(false);
        lineRenderable.setShadowCaster(false);
        previewLine = new Node();
        previewLine.setParent(arFragment.getArSceneView().getScene());
        previewLine.setRenderable(lineRenderable);

        ViewRenderable.builder()
                .setView(this, R.layout.view_distance_label)
                .setSizer((ViewSizer) view -> new Vector3(
                        LABEL_WIDTH_METERS, LABEL_HEIGHT_METERS, 1f))
                .build()
                .thenAccept(labelRenderable -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || arFragment == null) return;
                    previewLabel = new Node();
                    previewLabel.setParent(arFragment.getArSceneView().getScene());
                    previewLabel.setRenderable(labelRenderable);
                    previewView = (TextView) labelRenderable.getView();
                }));
    }

    private void setPreviewVisible(boolean visible) {
        if (previewLine != null) previewLine.setEnabled(visible);
        if (previewLabel != null) previewLabel.setEnabled(visible);
    }

    /** Place a readable label slightly beside the segment and face it toward the camera. */
    private void placeLabel(Node labelNode, TextView labelView, Vector3 center,
                            Vector3 direction, Vector3 cameraPosition, float meters,
                            boolean approximate) {
        if (labelNode == null) return;

        Vector3 toCamera = Vector3.subtract(cameraPosition, center).normalized();
        Vector3 perpendicular = new Vector3(
                direction.y * toCamera.z - direction.z * toCamera.y,
                direction.z * toCamera.x - direction.x * toCamera.z,
                direction.x * toCamera.y - direction.y * toCamera.x);
        float perpendicularLength = length(perpendicular);
        if (perpendicularLength < 0.0001f || Float.isNaN(perpendicularLength)) {
            perpendicular = Vector3.up();
        } else {
            perpendicular = perpendicular.scaled(1f / perpendicularLength);
        }

        Vector3 labelPosition = Vector3.add(center, perpendicular.scaled(0.04f));
        labelNode.setEnabled(true);
        labelNode.setWorldPosition(labelPosition);
        Vector3 lookDirection = Vector3.subtract(cameraPosition, labelPosition).normalized();
        Vector3 lookUp = Math.abs(lookDirection.x * Vector3.up().x
                + lookDirection.y * Vector3.up().y
                + lookDirection.z * Vector3.up().z) > 0.98f
                ? new Vector3(0f, 0f, 1f)
                : Vector3.up();
        labelNode.setLookDirection(lookDirection, lookUp);

        if (labelView != null) {
            String label = formatMeasuredDistance(meters, approximate);
            if (!label.equals(labelView.getText().toString())) {
                labelView.setText(label);
            }
        }
    }

    // ---------------- HUD, mode, undo, unit and clear ----------------

    private void updateHudNow() {
        if (hudText == null || arFragment == null) return;
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null || frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            hudText.setText(R.string.hud_locating);
            return;
        }

        int count = points.size();
        if (count == 0) {
            hudText.setText(R.string.hud_start);
            return;
        }
        if (count == 1) {
            hudText.setText(R.string.hud_one_point);
            return;
        }

        float total = 0f;
        boolean totalApproximate = false;
        for (MeasureSegment segment : segments) {
            if (segment.start == null || segment.end == null) continue;
            total += distance(segment.start.marker.getWorldPosition(),
                    segment.end.marker.getWorldPosition());
            totalApproximate |= isApproximate(segment.start.quality)
                    || isApproximate(segment.end.quality);
        }

        if (count == 2) {
            hudText.setText(getString(R.string.hud_result_line,
                    formatMeasuredDistance(total, totalApproximate)));
        } else {
            float straight = distance(points.get(0).marker.getWorldPosition(),
                    points.get(count - 1).marker.getWorldPosition());
            boolean straightApproximate = isApproximate(points.get(0).quality)
                    || isApproximate(points.get(count - 1).quality);
            hudText.setText(getString(R.string.hud_result_poly,
                    formatMeasuredDistance(total, totalApproximate),
                    formatMeasuredDistance(straight, straightApproximate)));
        }
    }

    private void toggleMeasurementMode() {
        if (measureMode == MeasureMode.PRECISION) {
            measureMode = MeasureMode.QUICK_ESTIMATE;
            showTip(getString(R.string.tip_mode_quick));
        } else {
            measureMode = MeasureMode.PRECISION;
            showTip(getString(R.string.tip_mode_precision));
        }
        updateModeButton();
        updateQualityStatus();
        updatePlaceButton();
    }

    private void updateModeButton() {
        if (modeButton == null) return;
        modeButton.setText(measureMode == MeasureMode.PRECISION
                ? R.string.mode_precision_button
                : R.string.mode_quick_button);
    }

    private void updatePlaceButton() {
        if (placeButton == null) return;
        boolean materialsReady = pointMaterial != null
                && (points.isEmpty() || lineMaterial != null);
        boolean enabled = cameraTracking && targetStable
                && isPlaceable(currentTarget) && materialsReady;
        if (placeButton.isEnabled() != enabled) {
            placeButton.setEnabled(enabled);
        }
    }

    private void showTip(CharSequence text) {
        if (tipText == null) return;
        tipText.removeCallbacks(hideTipRunnable);
        tipText.setText(text);
        tipText.setVisibility(View.VISIBLE);
        tipText.postDelayed(hideTipRunnable, 2200L);
    }

    private void undoLast() {
        int count = points.size();
        if (count == 0) return;

        MeasurePoint last = points.remove(count - 1);
        last.removed = true;
        detachPoint(last);
        if (!segments.isEmpty()) {
            MeasureSegment segment = segments.remove(segments.size() - 1);
            if (segment.lineNode != null) segment.lineNode.setParent(null);
            if (segment.labelNode != null) segment.labelNode.setParent(null);
        }
        updateHudNow();
        updatePlaceButton();
    }

    private void clearAll() {
        for (MeasurePoint point : points) {
            point.removed = true;
            detachPoint(point);
        }
        for (MeasureSegment segment : segments) {
            if (segment.lineNode != null) segment.lineNode.setParent(null);
            if (segment.labelNode != null) segment.labelNode.setParent(null);
        }
        points.clear();
        segments.clear();
        setPreviewVisible(false);
        updateHudNow();
        updatePlaceButton();
    }

    private void detachPoint(MeasurePoint point) {
        if (point.fixedNode != null) {
            Anchor anchor = point.fixedNode.getAnchor();
            if (anchor != null) anchor.detach();
            point.fixedNode.setParent(null);
        }
        if (point.marker != null) point.marker.setParent(null);
    }

    private void showUnitDialog() {
        String[] names = getResources().getStringArray(R.array.units);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_unit)
                .setItems(names, (dialog, which) -> {
                    unit = Unit.values()[which];
                    updateHudNow();
                })
                .show();
    }

    private String formatMeasuredDistance(float meters, boolean approximate) {
        String value;
        switch (unit) {
            case METER:
                value = String.format(Locale.US, "%.2f m", meters);
                break;
            case CENTIMETER:
                value = String.format(Locale.US, "%.1f cm", meters * 100f);
                break;
            case FOOT:
                value = String.format(Locale.US, "%.2f ft", meters * 3.28084f);
                break;
            case INCH:
                value = String.format(Locale.US, "%.1f in", meters * 39.3701f);
                break;
            default:
                value = String.format(Locale.US, "%.2f m", meters);
                break;
        }
        return approximate ? "≈ " + value : value;
    }

    private static boolean isApproximate(HitQuality quality) {
        return quality == HitQuality.INSTANT_APPROXIMATE;
    }

    private static float distance(Vector3 first, Vector3 second) {
        return length(Vector3.subtract(first, second));
    }

    private static float length(Vector3 vector) {
        return (float) Math.sqrt(vector.x * vector.x
                + vector.y * vector.y
                + vector.z * vector.z);
    }

    @Override
    protected void onDestroy() {
        if (tipText != null) tipText.removeCallbacks(hideTipRunnable);
        clearAll();
        super.onDestroy();
        arFragment = null;
    }

    private static Color rgb(int red, int green, int blue) {
        return new Color(red / 255f, green / 255f, blue / 255f, 1f);
    }
}
