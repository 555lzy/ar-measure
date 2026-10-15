package com.qh.armeasure;

import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.sceneform.ux.ArFragment;

/**
 * 自定义 ArFragment:
 *  - Instant Placement(即时落点):不等平面识别,点哪就落哪(ARCore 1.28+ 的 LOCAL_Y_UP 模式,
 *    对应原版"对准哪里点哪里"的跟手体验)
 *  - 水平 + 垂直平面都检测,开启聚焦模式提高精度
 */
public class MeasureArFragment extends ArFragment {

    @Override
    protected Config getSessionConfiguration(Session session) {
        Config config = new Config(session);
        config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP);
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
        config.setFocusMode(Config.FocusMode.AUTO);
        return config;
    }
}
