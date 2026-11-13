package top.qiyuey.liquidglass;

import android.app.Activity;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** WeCom's SwitchTab is a custom ViewGroup, not a LinearLayout or ViewPager. */
final class WeWorkAdapter implements HostAdapter {
    static final String BAR = "com.tencent.wework.common.views.SwitchTab";
    private static final String TAB = "com.tencent.wework.common.views.CommonTabView";
    private static final String MAIN_TAB = "com.tencent.wework.common.views.MainBottomTabView";
    private static final int INDEX_TAG = 0x7F5A0010;
    private static final int MOUNT_TAG = 0x7F5A0011;
    private static final int CONTENT_TRANSLATION_TAG = 0x7F5A0012;
    private static Method sSetMeasuredDimension;

    WeWorkAdapter() {}

    private boolean isBar(View view) {
        return view != null
                && BAR.equals(view.getClass().getName());
    }

    @Override public boolean isTab(View view) {
        if (view == null) return false;
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            if (TAB.equals(c.getName())) return true;
        }
        return false;
    }

    @Override public boolean isTabIcon(View view) {
        if (view == null) return false;
        // 5.0.13 MainBottomTabView.n is the main icon; .p is a separate indicator.
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (MAIN_TAB.equals(p.getClass().getName())) {
                return field(p, "n") == view;
            }
            if (p instanceof LiquidGlassHostLayout) break;
        }
        return false;
    }

    static Object field(Object instance, String name) {
        if (instance == null) return null;
        for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(instance);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** Preserve SwitchTab's original array positions; do not rewrite its tags or arrays. */
    @Override public void prepareTabs(ViewGroup bar) {
        if (!isBar(bar)) return;
        int index = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            if (!isTab(tab)) {
                tab.setVisibility(View.GONE); // SwitchTab also owns divider children.
                continue;
            }
            tab.setTag(INDEX_TAG, index++);
            if (shouldHide(tab)) tab.setVisibility(View.GONE);
            clearLabelBackgrounds(tab);
        }
    }

    private boolean isPrimaryLabel(View view) {
        if (!(view instanceof android.widget.TextView)) return false;
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent.getClass().getName().contains("RedPoint")) return false;
            if (isTab((View) parent)) {
                try {
                    Object title = parent.getClass().getMethod("getTitle").invoke(parent);
                    return title != null && title.toString().contentEquals(((android.widget.TextView) view).getText());
                } catch (ReflectiveOperationException e) { return false; }
            }
        }
        return false;
    }

    private void clearLabelBackgrounds(View view) {
        if (isPrimaryLabel(view)) {
            android.widget.TextView label = (android.widget.TextView) view;
            if (label.getBackground() != null) label.setBackground(null);
            if (label.getForeground() != null) label.setForeground(null);
            label.getPaint().bgColor = android.graphics.Color.TRANSPARENT;
            float size = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                    GlassStyle.LABEL_SIZE_SP, label.getResources().getDisplayMetrics());
            if (Math.abs(label.getTextSize() - size) > 0.5f) {
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, GlassStyle.LABEL_SIZE_SP);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearLabelBackgrounds(group.getChildAt(i));
        }
    }

    /** Center the icon/label stack, moving each top-level subtree once, badges included. */
    private void centerTabContents(ViewGroup tab) {
        int[] bounds = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        contentBounds(tab, tab, bounds);
        if (bounds[1] <= bounds[0] || tab.getHeight() <= 0) return;
        float offset = GlassStyle.contentCenterOffset(tab.getHeight(), bounds[0], bounds[1]);
        for (int i = 0; i < tab.getChildCount(); i++) {
            View child = tab.getChildAt(i);
            Object saved = child.getTag(CONTENT_TRANSLATION_TAG);
            if (!(saved instanceof Float)) {
                saved = child.getTranslationY();
                child.setTag(CONTENT_TRANSLATION_TAG, saved);
            }
            float target = (Float) saved + offset;
            if (Math.abs(child.getTranslationY() - target) > 0.25f) child.setTranslationY(target);
        }
    }

    private void contentBounds(View view, ViewGroup tab, int[] bounds) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (isTabIcon(view) || isPrimaryLabel(view)) {
            int[] position = new int[2];
            if (view.getHeight() > 0 && ViewGeom.positionIn(view, tab, position)) {
                bounds[0] = Math.min(bounds[0], position[1]);
                bounds[1] = Math.max(bounds[1], position[1] + view.getHeight());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) contentBounds(group.getChildAt(i), tab, bounds);
        }
    }

    @Override public boolean redrawRestingTab() { return false; }
    @Override public boolean allowShapeFallback() { return false; }
    @Override public ViewGroup tabRow(ViewGroup bar) { prepareTabs(bar); return bar; }
    @Override public void extendPages(ViewGroup backdrop) {
        LiquidGlassInstaller.padFragmentPages(backdrop);
    }

    private boolean shouldHide(View tab) {
        // Titles are read from the app, never from screen coordinates or fixed positions.
        try {
            Method getter = tab.getClass().getMethod("getTitle");
            Object title = getter.invoke(tab);
            if (title != null && WeWorkTabPolicy.hideTitle(title.toString())) return true;
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    @Override public int logicalIndex(View tab, int position) {
        Object index = tab.getTag(INDEX_TAG);
        return index instanceof Integer ? (Integer) index : -1;
    }

    @Override public int selectedSlot(ViewGroup row) {
        if (!isBar(row)) return -1;
        try {
            int index = (Integer) row.getClass().getMethod("getSelectedTab").invoke(row);
            return TabBarBridge.slotForIndex(row, index);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return -1;
        }
    }

    @Override public void installHooks(ClassLoader loader) throws ReflectiveOperationException {
        installWatermarkHook(loader);
        Class<?> barClass = loader.loadClass(BAR);
        sSetMeasuredDimension = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
        sSetMeasuredDimension.setAccessible(true);
        // The host reapplies the docked label's solid background after theme/selection changes.
        // Suppress only those writes for actual bottom-tab titles, including the magnified copy.
        LiquidGlassModule.hookIntercept(View.class.getDeclaredMethod("setBackgroundColor", int.class), chain -> {
            View view = (View) chain.getThisObject();
            if (isPrimaryLabel(view)) {
                if (view.getBackground() != null) view.setBackground(null);
                return null;
            }
            return chain.proceed();
        });
        LiquidGlassModule.hookIntercept(View.class.getDeclaredMethod("setBackground", android.graphics.drawable.Drawable.class), chain -> {
            if (chain.getArg(0) != null && isPrimaryLabel((View) chain.getThisObject())) return null;
            return chain.proceed();
        });
        // Rebuilds happen when the account/server changes available tabs.
        for (Method method : barClass.getDeclaredMethods()) {
            if ("setTabView".equals(method.getName())) {
                LiquidGlassModule.hookAfter(method, chain -> {
                    if (chain.getThisObject() instanceof ViewGroup) {
                        ViewGroup bar = (ViewGroup) chain.getThisObject();
                        prepareTabs(bar);
                        bar.requestLayout();
                    }
                });
            }
        }
        Method measure = barClass.getDeclaredMethod("onMeasure", int.class, int.class);
        LiquidGlassModule.hookIntercept(measure, chain -> {
            ViewGroup bar = (ViewGroup) chain.getThisObject();
            prepareTabs(bar);
            // Leave stock layout alone until this exact bar is in our floating host.
            if (!(bar.getParent() instanceof LiquidGlassHostLayout)) return chain.proceed();
            try {
                measureFloating(bar, (Integer) chain.getArg(0), (Integer) chain.getArg(1));
                return null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LiquidGlassModule.logErr("WeCom measure fallback", e);
                return chain.proceed();
            }
        });
        Method layout = barClass.getDeclaredMethod("onLayout", boolean.class,
                int.class, int.class, int.class, int.class);
        LiquidGlassModule.hookIntercept(layout, chain -> {
            ViewGroup bar = (ViewGroup) chain.getThisObject();
            if (!(bar.getParent() instanceof LiquidGlassHostLayout)) return chain.proceed();
            layoutFloating(bar);
            return null;
        });
    }

    private void installWatermarkHook(ClassLoader loader) {
        try {
            Class<?> overlay = loader.loadClass("com.tencent.wework.launch.AllWaterMaskView");
            // WeCom 5.0.13 shares this text renderer between the window overlay and page views.
            Class<?> renderer = overlay.getDeclaredField("d").getType();
            Method drawText = renderer.getDeclaredMethod("o", Canvas.class, int.class, int.class);
            if (drawText.getReturnType() == void.class) {
                LiquidGlassModule.hookIntercept(drawText, chain -> null);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LiquidGlassModule.logErr("WeCom watermark renderer unavailable", e);
        }
    }

    private void measureFloating(ViewGroup bar, int widthSpec, int heightSpec)
            throws ReflectiveOperationException {
        int count = TabBarBridge.tabCount(bar);
        if (count == 0) throw new IllegalStateException("WeCom has no visible tabs");
        int width = View.MeasureSpec.getSize(widthSpec);
        int height = View.MeasureSpec.getSize(heightSpec);
        if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED) {
            height = Math.round(56f * bar.getResources().getDisplayMetrics().density);
        }
        int available = Math.max(0, width - bar.getPaddingLeft() - bar.getPaddingRight());
        int contentHeight = Math.max(0, height - bar.getPaddingTop() - bar.getPaddingBottom());
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            boolean visibleTab = isTab(child) && child.getVisibility() != View.GONE;
            child.measure(View.MeasureSpec.makeMeasureSpec(visibleTab ? available / count : 0,
                            View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(visibleTab ? contentHeight : 0, View.MeasureSpec.EXACTLY));
        }
        sSetMeasuredDimension.invoke(bar, width, height);
    }

    private void layoutFloating(ViewGroup bar) {
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int x = rtl ? bar.getMeasuredWidth() - bar.getPaddingRight() : bar.getPaddingLeft();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (isTab(child) && child.getVisibility() != View.GONE) {
                int left = rtl ? x - child.getMeasuredWidth() : x;
                int right = left + child.getMeasuredWidth();
                child.layout(left, bar.getPaddingTop(), right, bar.getMeasuredHeight() - bar.getPaddingBottom());
                if (child instanceof ViewGroup) centerTabContents((ViewGroup) child);
                x = rtl ? left : right;
            } else {
                child.layout(0, 0, 0, 0);
            }
        }
    }

    private static final class Mount extends BarMount {
        final View reserve;
        Mount(FrameLayout parent, ViewGroup backdrop, View reserve) {
            super(parent, backdrop);
            this.reserve = reserve;
        }
    }

    /** Sample the app subtree while the floating bar is its sibling, avoiding recursion. */
    @Override public BarMount mount(Activity activity, ViewGroup bar) {
        if (activity == null) return null;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return null;
        View appRoot = bar;
        while (appRoot.getParent() instanceof View && appRoot.getParent() != content) {
            appRoot = (View) appRoot.getParent();
        }
        if (appRoot.getParent() != content || !(appRoot instanceof ViewGroup) || appRoot == bar) return null;
        Object candidate = field(activity, "mBottomTabContainer");
        View reserve = candidate instanceof View && candidate != bar ? (View) candidate : null;
        if (reserve != null) {
            boolean ancestor = false;
            for (android.view.ViewParent p = bar.getParent(); p instanceof View; p = p.getParent()) {
                if (p == reserve) { ancestor = true; break; }
            }
            if (!ancestor || reserve == appRoot || reserve.getHeight() > bar.getHeight() * 2) reserve = null;
        }
        Mount mount = new Mount((FrameLayout) content, (ViewGroup) appRoot, reserve);
        bar.setTag(MOUNT_TAG, mount);
        return mount;
    }

    @Override public void onMounted(ViewGroup bar) {
        Object tag = bar.getTag(MOUNT_TAG);
        if (!(tag instanceof Mount)) return;
        View reserve = ((Mount) tag).reserve;
        if (reserve != null && reserve.getLayoutParams() != null && reserve.getLayoutParams().height != 0) {
            ViewGroup.LayoutParams lp = reserve.getLayoutParams();
            lp.height = 0;
            reserve.setMinimumHeight(0);
            reserve.setLayoutParams(lp);
        }
    }

    @Override public void maintain(ViewGroup bar, View host) {
        prepareTabs(bar);
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (isTab(child) && child instanceof ViewGroup) centerTabContents((ViewGroup) child);
        }
        Object tag = bar.getTag(MOUNT_TAG);
        if (!(tag instanceof Mount)) return;
        View reserve = ((Mount) tag).reserve;
        if (reserve != null && host.getVisibility() != reserve.getVisibility()) {
            host.setVisibility(reserve.getVisibility());
        }
    }
}
