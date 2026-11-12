package top.qiyuey.liquidglass;

import android.content.Context;
import android.view.View;

/** WeChat's full-screen pager already shares a FrameLayout with the native bar. */
final class WeChatAdapter implements HostAdapter {
    @Override public boolean hideStockUntilReady() { return true; }
    @Override public boolean isTabIcon(View view) {
        return view.getClass().getName().endsWith("TabIconView");
    }
    @Override public int fallbackAccent() { return 0xFF07C160; }
    @Override public boolean configureWindow(Context context) {
        return LiquidGlassInstaller.enableEdgeToEdge(context);
    }
    @Override public void maintainWindow() { LiquidGlassInstaller.clearNavigationBar(); }
}
