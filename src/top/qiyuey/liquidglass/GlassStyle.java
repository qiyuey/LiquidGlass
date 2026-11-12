package top.qiyuey.liquidglass;

/** Shared Android design tokens; these numbers are project choices, not Apple HIG values. */
final class GlassStyle {
    static final float PANEL_PADDING_DP = 4f;
    static final float TAB_HEIGHT_DP = 56f;
    static final float MIN_TAB_WIDTH_DP = 76f;
    static final float MIN_TOUCH_WIDTH_DP = 48f;
    static final float HORIZONTAL_MARGIN_DP = 24f;
    static final float ICON_SIZE_DP = 24f;
    static final float LABEL_SIZE_SP = 11f;
    static final float LABEL_SPACING_DP = 1f;
    static final float BOTTOM_GAP_DP = 8f;
    static final float NO_INSET_BOTTOM_DP = 36f;
    static final float LAST_ROW_GAP_DP = 12f;
    static final float BLUR_DP = 4f;
    static final float SATURATION = 1.5f;
    static final float PANEL_REFRACTION_DP = 24f;
    static final float DROPLET_REFRACTION_DP = 10f;
    static final float DROPLET_REFRACTION_AMOUNT_DP = 14f;
    static final float CHROMATIC_ABERRATION = 0.5f;
    static final float PRESSED_SCALE = 78f / 56f;
    static final float TAB_ZOOM = 0.2f;
    static final float STRETCH_LIMIT = 0.2f;

    private GlassStyle() {}

    static int tabWidth(int contentWidth, int count, int windowWidth, float density) {
        if (count <= 0 || density <= 0f) return 0;
        int preferred = Math.max(Math.round(MIN_TAB_WIDTH_DP * density),
                contentWidth + Math.round(16f * density));
        int available = (windowWidth - Math.round(2f * HORIZONTAL_MARGIN_DP * density)
                - Math.round(2f * PANEL_PADDING_DP * density)) / count;
        return Math.min(preferred, Math.max(Math.round(MIN_TOUCH_WIDTH_DP * density), available));
    }

    static int panelHeight(float density, int labelHeight) {
        int tab = Math.max(Math.round(TAB_HEIGHT_DP * density),
                Math.round((ICON_SIZE_DP + LABEL_SPACING_DP + 12f) * density) + labelHeight);
        return tab + Math.round(2f * PANEL_PADDING_DP * density);
    }

    static float contentCenterOffset(int containerHeight, int contentTop, int contentBottom) {
        if (containerHeight <= 0 || contentBottom <= contentTop) return 0f;
        return (containerHeight - (contentBottom - contentTop)) * 0.5f - contentTop;
    }

    /** Subtract the inset already excluded by the parent, so it is paid exactly once. */
    static int bottomMargin(int navigationInset, int parentBottomGap, float density) {
        if (navigationInset <= 0) return Math.round(NO_INSET_BOTTOM_DP * density);
        return Math.max(0, navigationInset - Math.max(0, parentBottomGap))
                + Math.round(BOTTOM_GAP_DP * density);
    }
}
