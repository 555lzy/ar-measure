package com.qh.armeasure;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.google.ar.core.Anchor;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Material;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.rendering.ViewRenderable;

import java.util.ArrayList;
import java.util.List;

/**
 * AR 测距 v3 —— 对齐原版的手感:
 *
 *  1. Instant Placement:hitTest 直接给出 InstantPlacementPoint,不依赖已识别的平面
 *     —— "对准哪里点哪里",屏幕中心也有"落点"按钮(准星模式)。
 *  2. 三种命中来源统一处理:
 *       - 平面命中   → 固定 Anchor(平面根因下最稳)
 *       - IPP 命中   → 即时落点(点秒响应,随后逐帧精化)
 *       - 特征点命中 → 兜底
 *  3. 每帧重算:所有尺条/标签/距离数字跟随最新的点位置(IPP 精化也会实时反映)。
 *  4. 界面:中央准星 + 大"落点"按钮,支持点屏幕任意处与按钮两种落点方式。
 */
public class ArMeasureActivity extends AppCompatActivity {

    private enum Unit { CENTIMETER, METER, FOOT, INCH }

    private MeasureArFragment arFragment;
    private TextView hudText;
    private TextView tipText;
    private Unit unit = Unit.CENTIMETER;

    /** 一个测量点:固定锚 或 IPP,共用同一个 marker 节点取每帧世界坐标 */
    private static final class MeasurePoint {
        Node marker;                  // 世界坐标各帧刷新(IPP)或锚点子节点(固定)
        AnchorNode fixedNode;         // 平面命中时非空
        InstantPlacementPoint ipp;    // 即时落点时非空
    }

    private final List<MeasurePoint> points = new ArrayList<>();
    private final List<MeasureSegment> segments = new ArrayList<>();

    /** 两点间的一段尺条 + 距离标签(每帧动态贴点) */
    private static final class MeasureSegment {
        MeasurePoint start;
        MeasurePoint end;
        Node lineNode;
        Node labelNode;
        TextView labelView;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar_measure);
        hudText = findViewById(R.id.txt_hud);
        tipText = findViewById(R.id.txt_tip);
        Button btnPlace = findViewById(R.id.btn_place);
        Button btnUndo = findViewById(R.id.btn_undo);
        Button btnClear = findViewById(R.id.btn_clear);
        Button btnUnit = findViewById(R.id.btn_unit);
        btnPlace.setOnClickListener(v -> {
            // 准星模式:在屏幕正中落点
            View arView = arFragment == null ? null : arFragment.getView();
            if (arView == null) return;
            MotionEvent center = MotionEvent.obtain(
                    0, 0, MotionEvent.ACTION_DOWN,
                    arView.getWidth() / 2f, arView.getHeight() / 2f, 0);
            handleTap(center);
            center.recycle();
        });
        btnUndo.setOnClickListener(v -> undoLast());
        btnClear.setOnClickListener(v -> clearAll());
        btnUnit.setOnClickListener(v -> showUnitDialog());

        FragmentManager fm = getSupportFragmentManager();
        arFragment = (MeasureArFragment) fm.findFragmentById(R.id.ar_fragment);
        if (arFragment == null) {
            finish();
            return;
        }

        // 点屏幕任意处也落点
        arFragment.getArSceneView().getScene().setOnTouchListener((hitTestResult, motionEvent) -> {
            if (motionEvent.getAction() != MotionEvent.ACTION_UP) {
                return false;
            }
            handleTap(motionEvent);
            return true;
        });

        // 每帧重算所有内容
        arFragment.getArSceneView().getScene().addOnUpdateListener(this::onSceneUpdate);
    }

    // ---------------- 命中与落点 ----------------

    private void handleTap(MotionEvent motionEvent) {
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null || frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            showTip(getString(R.string.tip_locating));
            return;
        }
        List<HitResult> hits = frame.hitTest(motionEvent);
        HitResult chosen = chooseBestHit(hits);
        if (chosen == null) {
            showTip(getString(R.string.tip_no_surface));
            return;
        }
        placePoint(chosen);
    }

    /** 平面(多边形内) > 即时落点 IPP > 平面(扩展) > 特征点 */
    private static HitResult chooseBestHit(List<HitResult> hits) {
        if (hits == null) {
            return null;
        }
        HitResult planeInPoly = null, planeAny = null, pointHit = null, ippHit = null;
        for (HitResult h : hits) {
            if (h == null || h.getTrackable() == null
                    || h.getTrackable().getTrackingState() != TrackingState.TRACKING) {
                continue;
            }
            Trackable t = h.getTrackable();
            if (t instanceof Plane) {
                if (planeInPoly == null && ((Plane) t).isPoseInPolygon(h.getHitPose())) {
                    planeInPoly = h;
                } else if (planeAny == null) {
                    planeAny = h;
                }
            } else if (t instanceof InstantPlacementPoint && ippHit == null) {
                ippHit = h;
            } else if (t instanceof Point && pointHit == null) {
                pointHit = h;
            }
        }
        if (planeInPoly != null) return planeInPoly;
        if (ippHit != null) return ippHit;
        if (planeAny != null) return planeAny;
        return pointHit;
    }

    private void placePoint(HitResult chosen) {
        try {
            final MeasurePoint mp = new MeasurePoint();
            Trackable t = chosen.getTrackable();

            MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0x3B, 0x30))
                    .thenAccept(mat -> {
                        if (t instanceof InstantPlacementPoint) {
                            // 即时落点:marker 直接挂场景,每帧吸着 IPP 的 pose 移动
                            mp.ipp = (InstantPlacementPoint) t;
                            ModelRenderable sphere = ShapeFactory.makeSphere(0.010f,
                                    Vector3.zero(), mat);
                            Node marker = new Node();
                            marker.setParent(arFragment.getArSceneView().getScene());
                            marker.setRenderable(sphere);
                            mp.marker = marker;
                        } else {
                            // 平面/特征点:固定锚,一锤定音,最稳
                            Anchor anchor = chosen.createAnchor();
                            Pose pose = anchor.getPose();
                            AnchorNode node = new AnchorNode(anchor);
                            node.setParent(arFragment.getArSceneView().getScene());
                            Vector3 pos = new Vector3(pose.tx(), pose.ty(), pose.tz());
                            node.setWorldPosition(pos);
                            mp.fixedNode = node;
                            ModelRenderable sphere = ShapeFactory.makeSphere(0.010f, pos, mat);
                            Node marker = new Node();
                            marker.setParent(node);
                            marker.setLocalPosition(Vector3.zero());
                            marker.setRenderable(sphere);
                            mp.marker = marker;
                        }
                        int n = points.size();
                        if (n > 0) {
                            addSegment(points.get(n - 1), mp);
                        }
                        points.add(mp);
                        updateHud();
                    });
        } catch (Exception ignore) {
            // 会话异常/锚点耗尽,忽略本次
        }
    }

    /** 两点之间一段尺条:1cm×1cm×1 立方体,每帧按两点真实位置缩放/摆放 */
    private void addSegment(MeasurePoint start, MeasurePoint end) {
        MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0xC1, 0x07))
                .thenAccept(material -> {
                    final MeasureSegment seg = new MeasureSegment();
                    seg.start = start;
                    seg.end = end;

                    ModelRenderable lineR = ShapeFactory.makeCube(
                            new Vector3(0.008f, 0.008f, 1.0f), Vector3.zero(), material);
                    lineR.setShadowReceiver(false);
                    lineR.setShadowCaster(false);
                    Node lineNode = new Node();
                    lineNode.setParent(arFragment.getArSceneView().getScene());
                    lineNode.setRenderable(lineR);
                    seg.lineNode = lineNode;

                    ViewRenderable.builder()
                            .setView(this, R.layout.view_distance_label)
                            .build()
                            .thenAccept(labelR -> {
                                Node labelNode = new Node();
                                labelNode.setParent(arFragment.getArSceneView().getScene());
                                labelNode.setRenderable(labelR);
                                seg.labelNode = labelNode;
                                seg.labelView = (TextView) labelR.getView();
                            });
                    segments.add(seg);
                });
    }

    // ---------------- 每帧动态贴点 ----------------

    private void onSceneUpdate(com.google.ar.sceneform.FrameTime frameTime) {
        // 1. IPP 的 marker 每帧吸到它最新的 pose 上(即时落点的精化从这里体现)
        for (MeasurePoint mp : points) {
            if (mp.ipp != null && mp.marker != null) {
                Pose p = mp.ipp.getPose();
                mp.marker.setWorldPosition(new Vector3(p.tx(), p.ty(), p.tz()));
            }
        }
        if (arFragment == null) {
            return;
        }
        // 2. 尺条与标签
        Vector3 camPos = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();
        for (MeasureSegment seg : segments) {
            if (seg.lineNode == null || seg.start == null || seg.end == null) {
                continue;
            }
            Vector3 p0 = seg.start.marker.getWorldPosition();
            Vector3 p1 = seg.end.marker.getWorldPosition();
            Vector3 diff = Vector3.subtract(p1, p0);
            float len = diff.length();
            if (len < 0.005f) {
                seg.lineNode.setEnabled(false);
                if (seg.labelNode != null) seg.labelNode.setEnabled(false);
                continue;
            }
            Vector3 center = Vector3.add(p0, p1).scaled(0.5f);
            Vector3 dir = diff.normalized();
            seg.lineNode.setEnabled(true);
            seg.lineNode.setWorldPosition(center);
            seg.lineNode.setLocalRotation(Quaternion.rotationBetweenVectors(
                    new Vector3(0f, 0f, 1f), dir));
            seg.lineNode.setWorldScale(new Vector3(1f, 1f, len));

            if (seg.labelNode != null) {
                seg.labelNode.setEnabled(true);
                Vector3 labelPos = new Vector3(center.x, center.y + 0.06f, center.z);
                seg.labelNode.setWorldPosition(labelPos);
                Vector3 faceDir = Vector3.subtract(camPos, labelPos).normalized();
                seg.labelNode.setLookDirection(faceDir, Vector3.up());
                if (seg.labelView != null) {
                    seg.labelView.setText(formatDistance(len));
                }
            }
        }
        updateHud();
    }

    // ---------------- HUD / 撤销 / 单位 / 清空 ----------------

    private void updateHud() {
        if (hudText == null || arFragment == null) return;
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame != null && frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            hudText.setText(R.string.hud_locating);
            return;
        }
        int n = points.size();
        if (n < 2) {
            hudText.setText(getString(R.string.hud_hint, n));
            return;
        }
        float total = 0f;
        for (MeasureSegment seg : segments) {
            if (seg.start != null && seg.end != null) {
                total += Vector3.subtract(seg.start.marker.getWorldPosition(),
                        seg.end.marker.getWorldPosition()).length();
            }
        }
        if (n == 2) {
            hudText.setText(getString(R.string.hud_result_line, formatDistance(total)));
        } else {
            float straight = Vector3.subtract(points.get(0).marker.getWorldPosition(),
                    points.get(n - 1).marker.getWorldPosition()).length();
            hudText.setText(getString(R.string.hud_result_poly,
                    formatDistance(total), formatDistance(straight)));
        }
    }

    private void showTip(CharSequence text) {
        if (tipText != null) {
            tipText.setText(text);
            tipText.setVisibility(View.VISIBLE);
            tipText.postDelayed(() -> {
                if (tipText != null) tipText.setVisibility(View.GONE);
            }, 1500);
        }
    }

    private void undoLast() {
        int n = points.size();
        if (n == 0) return;
        MeasurePoint last = points.remove(n - 1);
        if (last.fixedNode != null) {
            Anchor a = last.fixedNode.getAnchor();
            if (a != null) a.detach();
            last.fixedNode.setParent(null);
        }
        if (last.marker != null) last.marker.setParent(null);
        if (!segments.isEmpty()) {
            MeasureSegment seg = segments.remove(segments.size() - 1);
            if (seg.lineNode != null) seg.lineNode.setParent(null);
            if (seg.labelNode != null) seg.labelNode.setParent(null);
        }
        updateHud();
    }

    private void clearAll() {
        for (MeasurePoint mp : points) {
            if (mp.fixedNode != null) {
                Anchor a = mp.fixedNode.getAnchor();
                if (a != null) a.detach();
                mp.fixedNode.setParent(null);
            }
            if (mp.marker != null) mp.marker.setParent(null);
        }
        for (MeasureSegment seg : segments) {
            if (seg.lineNode != null) seg.lineNode.setParent(null);
            if (seg.labelNode != null) seg.labelNode.setParent(null);
        }
        points.clear();
        segments.clear();
        updateHud();
    }

    private void showUnitDialog() {
        String[] names = getResources().getStringArray(R.array.units);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_unit)
                .setItems(names, (dialog, which) -> {
                    unit = Unit.values()[which];
                    updateHud();
                })
                .show();
    }

    private String formatDistance(float meters) {
        switch (unit) {
            case METER:      return String.format("%.2f m", meters);
            case CENTIMETER: return String.format("%.1f cm", meters * 100f);
            case FOOT:       return String.format("%.2f ft", meters * 3.28084f);
            case INCH:       return String.format("%.1f in", meters * 39.3701f);
            default:         return String.format("%.2f m", meters);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearAll();
        arFragment = null;
    }

    private static Color rgb(int r, int g, int b) {
        return new Color(r / 255f, g / 255f, b / 255f, 1f);
    }
}
