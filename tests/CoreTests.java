package top.qiyuey.liquidglass;

/** JVM checks for geometry, explicit tab filtering and continuous spring retargeting. */
public final class CoreTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(GlassStyle.panelHeight(1f, 14) == 64, "default capsule is 64dp");
        check(GlassStyle.panelHeight(1f, 40) == 85, "large labels expand the capsule");
        check(GlassStyle.tabWidth(24, 2, 400, 1f) * 2 + 8 == 160, "two content-sized tabs");
        check(GlassStyle.tabWidth(24, 3, 400, 1f) * 3 + 8 == 236, "three content-sized tabs");
        check(GlassStyle.tabWidth(150, 4, 320, 1f) == 66, "long titles respect window margins");
        check(GlassStyle.tabWidth(24, 4, 200, 1f) >= 48, "touch targets never shrink below 48dp");
        check(GlassStyle.bottomMargin(24, 0, 1f) == 32, "edge-to-edge includes navigation inset");
        check(GlassStyle.bottomMargin(24, 24, 1f) == 8, "inset excluded by parent is not counted twice");
        check(GlassStyle.bottomMargin(0, 0, 1f) == 36, "zero-inset spacing");
        check(GlassStyle.contentCenterOffset(56, 12, 54) == -5f, "low native content moves upward");
        check(GlassStyle.contentCenterOffset(56, 5, 39) == 6f, "high native content moves downward");
        for (String title : new String[]{"Channel", " CHANNELS ", "Doc", "Docs", "Documents", "频道", "文檔"}) {
            check(WeWorkTabPolicy.hideTitle(title), "explicit hidden title: " + title);
        }
        for (String title : new String[]{null, "", "Workspace", "Messages", "Contacts", "Documentary", "My Docs"}) {
            check(!WeWorkTabPolicy.hideTitle(title), "unrelated title stays visible: " + title);
        }
        Spring spring = new Spring(1f, 1000f, 0.001f, 0f);
        spring.animateTo(3f);
        spring.update(1f / 60f);
        float position = spring.value();
        float velocity = spring.velocity();
        spring.animateTo(1f);
        check(spring.value() == position && spring.velocity() == velocity,
                "retargeting preserves position and velocity");
        for (int i = 0; i < 300; i++) {
            spring.update(i == 2 ? 0.5f : 1f / 60f);
            check(Float.isFinite(spring.value()) && Float.isFinite(spring.velocity()), "dropped frames remain finite");
        }
        check(!spring.isRunning() && spring.value() == 1f, "spring settles at the latest target");
        System.out.println("Core checks passed: geometry, insets, centering, filtering, spring continuity.");
    }
}
