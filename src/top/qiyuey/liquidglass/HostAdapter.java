package top.qiyuey.liquidglass;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Strategy boundary: the rendering engine knows views, never host identities. */
interface HostAdapter {
    default void installHooks(ClassLoader loader) throws ReflectiveOperationException {}
    default boolean allowShapeFallback() { return true; }
    default boolean hideStockUntilReady() { return false; }
    default ViewGroup tabRow(ViewGroup bar) { return null; }
    default boolean isTab(View child) { return true; }
    default boolean preferTextColorProbe() { return false; }
    default boolean isTabIcon(View child) { return false; }
    default void prepareTabs(ViewGroup bar) {}
    default void onMounted(ViewGroup bar) {}
    default void maintain(ViewGroup bar, View host) {}
    default int fallbackAccent() { return 0xFF267EF0; }
    default int logicalIndex(View child, int position) {
        Object tag = child.getTag();
        return tag instanceof Integer ? (Integer) tag : position;
    }
    default int selectedSlot(ViewGroup row) {
        int slot = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!isTab(child) || child.getVisibility() == View.GONE) continue;
            if (child.isSelected()) return slot;
            slot++;
        }
        return -1;
    }
    default BarMount mount(Activity activity, ViewGroup bar) {
        if (!(bar.getParent() instanceof android.widget.FrameLayout)) return null;
        ViewGroup parent = (ViewGroup) bar.getParent();
        ViewGroup backdrop = null;
        int area = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int nextArea = child.getWidth() * child.getHeight();
            if (child != bar && child instanceof ViewGroup && child.getVisibility() == View.VISIBLE
                    && nextArea > area) {
                backdrop = (ViewGroup) child;
                area = nextArea;
            }
        }
        return backdrop == null ? null : new BarMount(parent, backdrop);
    }
    default void extendPages(ViewGroup backdrop) { LiquidGlassInstaller.extendPagerPages(backdrop); }
    default boolean configureWindow(Context context) { return false; }
    default void maintainWindow() {}
}
