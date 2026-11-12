package top.qiyuey.liquidglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;


final class LiquidGlassInstaller {


    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_DELAY_MS = 250L;

    /** Scratch for on-screen positions; every use is on the UI thread. */
    private static final int[] sLoc = new int[2];
    /** Scratch for host-relative positions; every use is on the UI thread. */
    private static final int[] sPos = new int[2];
    private static boolean sKeepFailed;
    private static WeakReference<Activity> sActivityRef = new WeakReference<>(null);
    private static WeakReference<LiquidGlassHostLayout> sHostRef = new WeakReference<>(null);
    private static WeakReference<View> sDropletRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sTabRowRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sPagerRef = new WeakReference<>(null);
    private static int sLastIndex = -1;
    private static WeakReference<View> sTabViewRef = new WeakReference<>(null);
    private static WeakReference<View> sGlassRef = new WeakReference<>(null);
    private static DropletDragController sDrag;
    /** Identity/children fingerprint of the row the renderer is currently bound to. */
    private static int sTabStructureSignature;
    private static boolean sTabStructureRefreshPosted;
    /**
     * Uptime since which the live tab row has been unusable while the old
     * binding is dead too, or 0. Timed rather than counted in frames: with
     * nothing moving no frames come, and the wait would never run out.
     */
    private static long sTabRowEmptySinceMs;
    private static final long TAB_ROW_EMPTY_GIVE_UP_MS = 2000L;

    private static int sBarHeight;

    private static int sNavigationInset;
    /** The app's own frosted strip and the bar's hairline, held hidden. */
    private static WeakReference<View> sBlurLayerRef = new WeakReference<>(null);
    private static WeakReference<View> sHairlineRef = new WeakReference<>(null);
    private static boolean sBlurRelit;
    /** Droplet's resting Y inside the host, before WeChat's bar offset. */
    private static float sDropletBaseY;
    /** Geometry the droplet's size and offset were last computed against. */
    private static int sDropletGeometryKey;
    private static boolean sDropletLogged;

    private LiquidGlassInstaller() {
    }

    /**
     * How long the stock bar stays hidden before it is handed back. Sized to
     * outlast the install polling above, so a slow cold start does not flash
     * the original bar a beat before the pill replaces it.
     */
    private static final long REVEAL_TIMEOUT_MS = 8000L;

    /**
     * Keeps the app's own bar invisible until the pill takes it over.
     *
     * <p>Installing cannot happen before the first layout, and the app has drawn
     * its own bar by then — which reads as a flash of the original on every cold
     * start. Hooking the bar's constructor would be the natural place to catch
     * it, but Tinker means the class our loader resolves is not the one the live
     * view comes from, so the hook never fires. Matching on the class name from
     * a pre-draw listener sidesteps that entirely, and pre-draw is the last
     * point before anything reaches the screen.
     *
     * <p>The timeout hands the bar back if the pill never arrives: a permanently
     * invisible tab bar would be far worse than a flash.
     */
    private static void hideStockBarUntilInstalled(View decor) {
        decor.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private final long deadline =
                            android.os.SystemClock.uptimeMillis() + REVEAL_TIMEOUT_MS;
                    private View bar;

                    @Override
                    public boolean onPreDraw() {
                        if (bar == null || bar.getParent() == null) {
                            bar = TabBarBridge.findTabView(decor);
                        }
                        boolean installed = bar != null
                                && bar.getParent() instanceof LiquidGlassHostLayout;
                        boolean expired =
                                android.os.SystemClock.uptimeMillis() > deadline;
                        if (installed || expired) {
                            if (bar != null && !installed) {
                                bar.setAlpha(1f);
                                if (bar.getParent() instanceof ViewGroup) {
                                    showOwnBlurLayers((ViewGroup) bar.getParent());
                                }
                                LiquidGlassModule.log(android.util.Log.WARN,
                                        "pill never took over, stock bar restored");
                            }
                            decor.getViewTreeObserver().removeOnPreDrawListener(this);
                        } else if (bar != null && bar.getAlpha() != 0f) {
                            bar.setAlpha(0f);
                            // stays lit with the bar faded out, which reads as
                            // a grey band across the bottom for the whole cold
                            // start. It goes at the same moment the bar does.
                            if (bar.getParent() instanceof ViewGroup) {
                                hideOwnBlurLayers((ViewGroup) bar.getParent(), bar);
                            }
                        }
                        return true;
                    }
                });
    }

    static void scheduleInstall(Activity activity) {
        // Kept for extendUnderNavBar: WeChat's views hand out a context that does
        // not wrap the Activity, so the window is not reachable from them.
        sActivityRef = new WeakReference<>(activity);
        View decor = activity.getWindow().getDecorView();
        if (sHostRef.get() == null && LiquidGlassModule.app().adapter.hideStockUntilReady()) {
            hideStockBarUntilInstalled(decor);
        }
        decor.post(() -> tryInstall(activity, decor, 0));
    }

    private static void tryInstall(Activity activity, View decor, int attempt) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            // Only skip if the pill is live in *this* window. WeChat's process
            // outlives a swipe-away from recents, so these statics still point at
            // the destroyed Activity's host — treating that as "already installed"
            // left the relaunched LauncherUI with its stock bar.
            LiquidGlassHostLayout live = sHostRef.get();
            if (live != null && live.isAttachedToWindow()
                    && live.getRootView() == decor.getRootView()) {
                // Nothing to install, but coming back from a chat or any other
                // screen rebuilds the window state: the navigation inset is
                // applied again and the dead strip at the bottom re-opens.
                reassertBottom(activity);
                return;
            }
            if (live != null) {
                resetState();
            }
            ViewGroup tabView = TabBarBridge.locateTabView(decor);
            if (tabView == null) {
                if (attempt < MAX_ATTEMPTS) {
                    decor.postDelayed(
                            () -> tryInstall(activity, decor, attempt + 1),
                            RETRY_DELAY_MS);
                } else {
                    LiquidGlassModule.log(android.util.Log.WARN,
                            "tab bar not found after " + MAX_ATTEMPTS
                                    + " attempts, giving up; tree="
                                    + TabBarBridge.describeTree(decor));
                }
                return;
            }
            install(tabView);
            if (!(tabView.getParent() instanceof LiquidGlassHostLayout)) {
                tabView.setAlpha(1f);
                if (attempt < MAX_ATTEMPTS) {
                    decor.postDelayed(() -> tryInstall(activity, decor, attempt + 1), RETRY_DELAY_MS);
                }
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("install failed", t);
        }
    }

    /** Drops references to a previous Activity's views so a relaunch reinstalls. */
    private static void resetState() {
        sHostRef = new WeakReference<>(null);
        sTabViewRef = new WeakReference<>(null);
        sGlassRef = new WeakReference<>(null);
        sDropletRef = new WeakReference<>(null);
        sTabRowRef = new WeakReference<>(null);
        sPagerRef = new WeakReference<>(null);
        sDrag = null;
        sTabStructureSignature = 0;
        sTabStructureRefreshPosted = false;
        sTabRowEmptySinceMs = 0L;
        sBarHeight = 0;
        sBlurLayerRef = new WeakReference<>(null);
        sHairlineRef = new WeakReference<>(null);
        sNavBgRef = new WeakReference<>(null);
        sBlurRelit = false;
        sLastIndex = -1;
        sDropletBaseY = 0f;
        sDropletGeometryKey = 0;
        sDropletLogged = false;
        sPaddedScrollers.clear();
        sScrollerPad = 0;
        sPageBarDisagreeSinceMs = 0L;
        LiquidGlassModule.log(android.util.Log.INFO,
                "stale host from a previous Activity dropped, reinstalling");
    }

    /**
     * The pager the glass is currently refracting, or null before the first
     * install. Re-read on every use rather than captured: {@link #resetState}
     * drops it when an Activity is recreated and the next install publishes a
     * fresh instance, so a cached reference would go stale for the rest of the
     * process.
     */
    static ViewGroup currentPager() {
        return sPagerRef.get();
    }

    private static void install(ViewGroup tabView) {
        ViewGroup originalParent = tabView.getParent() instanceof ViewGroup
                ? (ViewGroup) tabView.getParent() : null;
        if (originalParent == null) {
            return;
        }
        if (originalParent instanceof LiquidGlassHostLayout) {
            return; // already installed
        }
        int originalIndex = originalParent.indexOfChild(tabView);
        HostAdapter adapter = LiquidGlassModule.app().adapter;
        BarMount mount = adapter.mount(sActivityRef.get(), tabView);
        if (mount == null) {
            LiquidGlassModule.log(android.util.Log.WARN, "Host content root unavailable; keep stock bar");
            return;
        }
        ViewGroup parent = mount.parent;
        ViewGroup backdrop = mount.backdrop;
        if (backdrop == null) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "no backdrop sibling found, glass would refract nothing");
            return;
        }

        int index = parent == originalParent ? originalIndex : parent.getChildCount();
        if (index < 0) {
            return;
        }
        ViewGroup.LayoutParams originalLp = tabView.getLayoutParams();

        Context ctx = tabView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int bottomOffset = GlassStyle.bottomMargin(navInset(parent), parentBottomGap(parent), density);

        int navigationInset = rememberNavigationInset(parent);

        LiquidGlassHostLayout host = new LiquidGlassHostLayout(ctx, backdrop, tabView);

        int navReserve = tabView.getPaddingBottom();
        // Read before the drop lands: the bar is still laid out at its old
        // height here, and the reserve is exactly what the pill must not keep.
        ViewGroup tabRow = TabBarBridge.findTabRow(tabView);
        if (tabRow == null || TabBarBridge.tabCount(tabRow) < 2) {
            tabView.setAlpha(1f);
            LiquidGlassModule.log(android.util.Log.WARN, "Native tab row is unresolved; keep stock layout");
            return;
        }
        int barHeight = Math.max(contentBarHeight(tabRow, tabView.getHeight() - navReserve),
                GlassStyle.panelHeight(density, maximumLabelHeight(tabRow)));

        // KernelSU's floating bar is width(IntrinsicSize.Min) — it hugs its tabs
        // and floats centred, rather than stretching edge to edge. The width has
        // to be resolved up front and pinned: leaving the host WRAP_CONTENT makes
        // the MATCH_PARENT glass layer measure to zero and vanish.
        // The shadow is drawn inside the host's own padding — setElevation never
        // renders anything in this view tree — so the host is inflated by that
        // much and pulled back down by the same amount.
        host.setupShadow(density, isNight(ctx));
        int shadowPad = host.shadowPad();

        TabGeometrySnapshot geometry = new TabGeometrySnapshot(tabView, tabRow);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        // The navigation inset only counts once the window reaches under it —
        // either because extendUnderNavBar grew it or because the app already
        // draws edge to edge. Short of that the pill's parent stops above the
        // gesture bar, and adding it again floats the pill far too high. Left
        // out here and applied below, once that is known.
        // Anchored on the parent, not the bar: the bar is about to be detached,
        // and a detached view reports no insets at all. Reading from it later
        // would silently drop the correction and let the bar sink onto the
        // gesture pill.
        hostLp.bottomMargin = bottomOffset - shadowPad;
        FrameLayout.LayoutParams tabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight > 0 ? barHeight : ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);

        // The native bar must never be left detached. All potentially failing
        // setup above runs while it still belongs to the app; this small
        // structural transaction either installs both levels or puts the bar
        // straight back at its original index.
        try {
            originalParent.removeView(tabView);
            parent.addView(host, index, hostLp);
            host.addView(tabView, tabLp);
        } catch (Throwable t) {
            restoreAfterFailedReparent(originalParent, tabView, host, originalIndex, originalLp);
            LiquidGlassModule.logErr("could not reparent the stock tab bar", t);
            return;
        }

        try {
            dropNavReserve(tabView);
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth > 0) {
                hostLp.width = barWidth + shadowPad * 2;
                host.setLayoutParams(hostLp);
            }
        } catch (Throwable t) {
            try {
                geometry.restore(density);
            } catch (Throwable restoreError) {
                LiquidGlassModule.logErr("could not restore stock tab geometry",
                        restoreError);
            }
            restoreAfterFailedReparent(originalParent, tabView, host, originalIndex, originalLp);
            LiquidGlassModule.logErr("could not size the floating tab bar", t);
            return;
        }

        // Safe to show again: the glass goes in below it in this same pass, so
        // the two appear together.
        tabView.setAlpha(1f);

        sHostRef = new WeakReference<>(host);
        sTabViewRef = new WeakReference<>(tabView);
        sTabRowRef = new WeakReference<>(tabRow);
        sTabStructureSignature = tabStructureSignature(tabRow);
        sTabStructureRefreshPosted = false;
        sPagerRef = new WeakReference<>(backdrop);
        sBarHeight = barHeight;
        sLastIndex = -1;

        adapter.onMounted(tabView);

        TabBarBridge.tryHookPager(backdrop);

        // Cosmetic cleanup cannot invalidate the structural install. If an app
        // skin changes one of these details, keep the functional floating bar
        // and report the degraded effect rather than trying to tear it back out.
        try {
            hideOwnBlurLayers(parent, tabView);
            hideBarHairline(parent, tabView);
            stripSolidBackgrounds(tabView);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not remove all stock tab chrome", t);
        }

        extendUnderNavBar(ctx);
        {
            hostLp.bottomMargin = GlassStyle.bottomMargin(navigationInset, parentBottomGap(parent), density) - shadowPad;
            host.setLayoutParams(hostLp);
            // The first real insets dispatch can arrive either side of host
            // creation. Re-read the shared cache on the next UI turn so both
            // orders converge on the same bottom anchor.
            host.post(() -> syncHostBottomInset(host, sNavigationInset));
        }

        // Open up clipping all the way to the content root: the droplet grows
        // past the pill while dragging, and any ancestor still clipping its
        // children would shear that overflow off.
        unclipAncestors(parent);
        attachRenderer(ctx, host, backdrop, density);
        installSelectionWatcher(host);
        watchBottomInset(host, backdrop);

        host.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean done;

                    @Override
                    public void onGlobalLayout() {
                        if (done) {
                            return;
                        }
                        done = true;
                        host.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        host.attach();
                        syncDropletSize(TabBarBridge.currentIndex(tabView));
                        extendPagesToBottom(backdrop);
                        // The window grows a beat after the flag is set, and the
                        // pages have to be re-stretched into the room that frees.
                        host.postDelayed(() -> extendPagesToBottom(backdrop), 500L);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "liquid glass installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " barH=" + tabView.getHeight()
                                        + " children=" + host.getChildCount());
                    }
                });
    }

    /** Restores the app-owned bar if the two-level reparent cannot complete. */
    private static void restoreAfterFailedReparent(
            ViewGroup parent, View tabView, LiquidGlassHostLayout host,
            int index, ViewGroup.LayoutParams originalLp) {
        try {
            if (tabView.getParent() instanceof ViewGroup
                    && tabView.getParent() != parent) {
                ((ViewGroup) tabView.getParent()).removeView(tabView);
            }
            if (host.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.getParent()).removeView(host);
            }
            if (tabView.getParent() == null) {
                int safeIndex = Math.max(0, Math.min(index, parent.getChildCount()));
                if (originalLp != null) {
                    parent.addView(tabView, safeIndex, originalLp);
                } else {
                    parent.addView(tabView, safeIndex);
                }
            }
            tabView.setAlpha(1f);
        } catch (Throwable restoreError) {
            LiquidGlassModule.logErr("could not restore the stock tab bar", restoreError);
        }
    }

    /** Reversible geometry touched while turning equal-width tabs into a pill. */
    private static final class TabGeometrySnapshot {
        private final View mTabView;
        private final int[] mTabPadding = new int[4];
        private final ViewGroup mRow;
        private final int[] mRowPadding = new int[4];
        private final View[] mTabs;
        private final int[] mWidths;
        private final float[] mWeights;
        private final boolean[] mHadLayoutParams;

        TabGeometrySnapshot(View tabView, ViewGroup row) {
            mTabView = tabView;
            mTabPadding[0] = tabView.getPaddingLeft();
            mTabPadding[1] = tabView.getPaddingTop();
            mTabPadding[2] = tabView.getPaddingRight();
            mTabPadding[3] = tabView.getPaddingBottom();
            mRow = row;
            int count = row == null ? 0 : row.getChildCount();
            mTabs = new View[count];
            mWidths = new int[count];
            mWeights = new float[count];
            mHadLayoutParams = new boolean[count];
            if (row == null) {
                return;
            }
            mRowPadding[0] = row.getPaddingLeft();
            mRowPadding[1] = row.getPaddingTop();
            mRowPadding[2] = row.getPaddingRight();
            mRowPadding[3] = row.getPaddingBottom();
            for (int i = 0; i < count; i++) {
                View tab = row.getChildAt(i);
                mTabs[i] = tab;
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                mHadLayoutParams[i] = true;
                mWidths[i] = lp.width;
                mWeights[i] = lp instanceof android.widget.LinearLayout.LayoutParams
                        ? ((android.widget.LinearLayout.LayoutParams) lp).weight : 0f;
            }
        }

        void restore(float density) {
            mTabView.setPadding(mTabPadding[0], mTabPadding[1],
                    mTabPadding[2], mTabPadding[3]);
            if (mRow == null) {
                return;
            }
            mRow.setPadding(mRowPadding[0], mRowPadding[1],
                    mRowPadding[2], mRowPadding[3]);
            for (int i = 0; i < mTabs.length; i++) {
                if (!mHadLayoutParams[i]) {
                    continue;
                }
                View tab = mTabs[i];
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                lp.width = mWidths[i];
                if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp).weight = mWeights[i];
                }
                tab.setLayoutParams(lp);
            }
        }
    }

    private static final int EXTEND_TAG_KEY = 0x7F5A0001;

    /**
     * Lets every page's content run to the bottom of the screen, so the list
     * keeps rendering behind and below the floating pill.
     *
     * <p>WeChat sizes each page to stop where the docked bar used to start. Once
     * the bar floats, that band would otherwise show bare page background. The
     * content is stretched to the full height and the scrolling views get bottom
     * padding, so rows scroll through underneath the pill and the last one can
     * still clear it.
     *
     * <p>WeChat re-applies its own LayoutParams on later layout passes (that is
     * why the Contacts page kept snapping back), so each container also gets a
     * layout listener that re-stretches it whenever that happens.
     */
    private static void extendPagesToBottom(ViewGroup backdrop) {
        if (backdrop != null) LiquidGlassModule.app().adapter.extendPages(backdrop);
    }

    static void padFragmentPages(ViewGroup backdrop) {
        View tab = sTabViewRef.get();
        if (tab instanceof ViewGroup) LiquidGlassModule.app().adapter.onMounted((ViewGroup) tab);
        int pad = bottomReserve(backdrop);
        if (pad > 0) padScrollersBottom(backdrop, pad, 0);
    }

    static void extendPagerPages(ViewGroup pager) {
        if (pager == null) return;
        // The pager itself first. A page can only be stretched as far as its
        // container reaches, and WeChat sizes the pager to stop where the docked
        // bar used to start. Left short, the band the bar vacated shows the root
        // layout's own background instead of the page — the pale strip that
        // appears under the floating pill. Its parent is the FrameLayout the bar
        // was pulled out of, so this is a plain margin/height fix with no
        // positioning rules to undo.
        ViewGroup pagerParent = pager.getParent() instanceof ViewGroup
                ? (ViewGroup) pager.getParent() : null;
        if (pagerParent != null) {
            stretchToBottom(pager, pagerParent.getHeight());
        }
        int target = pager.getHeight();
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page instanceof ViewGroup) {
                // And the page itself, for the same reason: WeChat sizes each
                // page to stop where the docked bar started, and a child can
                // only be stretched as far as the page it sits in. Left as it
                // is, the band the bar vacated shows the DecorView's own
                // background through the pager — the strip under the pill.
                dropBottomFrost(page);
                stretchToBottom(page, target);
                extendOnePage((ViewGroup) page, target);
            }
        }
    }


    private static void dropBottomFrostAll() {
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        for (int i = 0; i < pager.getChildCount(); i++) {
            dropBottomFrost(pager.getChildAt(i));
        }
    }

    /** Per-page-class cache for WeChat's optional frosted-band API. */
    private static final java.util.HashMap<Class<?>, Method[]> sFrostMethods =
            new java.util.HashMap<>();
    private static final java.util.HashSet<Class<?>> sNoFrostClasses =
            new java.util.HashSet<>();
    private static boolean sFrostLogged;

    private static void dropBottomFrost(View page) {
        Class<?> pageClass = page.getClass();
        Method[] methods = sFrostMethods.get(pageClass);
        if (methods == null && !sNoFrostClasses.contains(pageClass)) {
            try {
                methods = new Method[]{
                        pageClass.getMethod("getBottomBlurAreaHeight"),
                        pageClass.getMethod("setBottomBlurAreaHeight", int.class),
                };
                sFrostMethods.put(pageClass, methods);
            } catch (NoSuchMethodException e) {
                sNoFrostClasses.add(pageClass);
            } catch (Throwable t) {
                sNoFrostClasses.add(pageClass);
                LiquidGlassModule.logErr("frosted band lookup", t);
            }
        }
        if (methods == null) {
            return;
        }
        try {
            Object h = methods[0].invoke(page);
            if (!(h instanceof Integer) || (Integer) h == 0) {
                return;
            }
            methods[1].invoke(page, 0);
            if (!sFrostLogged) {
                sFrostLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "dropping the pages' " + h + "px frosted bottom band");
            }
        } catch (Throwable ignored) {
            // Per-frame path: a broken page must not spam the log.
        }
    }

    /**
     * @param targetHeight height the page is being stretched to. Passed in
     *     rather than read off the page because the stretch above only takes
     *     effect on the next layout pass, so the page still reports the old,
     *     short height on this one.
     */
    private static void extendOnePage(ViewGroup pg, int targetHeight) {
        int pageHeight = Math.max(pg.getHeight(), targetHeight);
        if (pageHeight <= 0) {
            return;
        }
        // Some pages (Contacts) reserve the bar's height as padding on the page
        // itself rather than as a child margin, which caps the content at 2434
        // no matter what its LayoutParams say. Drop it and let the scrollers
        // carry the inset instead.
        if (pg.getPaddingBottom() > 0) {
            pg.setClipToPadding(false);
            pg.setPadding(pg.getPaddingLeft(), pg.getPaddingTop(),
                    pg.getPaddingRight(), 0);
        }
        for (int i = 0; i < pg.getChildCount(); i++) {
            View c = pg.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE
                    || c.getHeight() < pageHeight / 2) {
                continue;
            }
            stretchToBottom(c, pageHeight);
            keepStretchedToBottom(c);
        }
        padScrollersBottom(pg, bottomReserve(pg), 0);
    }

    /** Breathing room between the last row and the pill once it is scrolled clear. */
    private static final float LAST_ROW_GAP_DP = GlassStyle.LAST_ROW_GAP_DP;

    /**
     * Room the floating bar takes up at the bottom of the screen.
     *
     * <p>Measured off the pill rather than assembled from the constants that
     * position it, so it stays right whatever the bar height, the float offset
     * or the navigation inset turn out to be.
     */
    private static int bottomReserve(View anchor) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host == null || host.getHeight() <= 0) {
            return 0;
        }
        // Unscaled: the pill grows while held and eases in on reveal, and a
        // stretch that ran mid-way through either used to hand the lists a
        // padding they had to relayout for, twice.
        ViewGeom.unscaledScreenPos(host, sLoc);
        // Undo the offset followBarOffset applies while the bar slides away.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        if (root == null || root.getHeight() <= 0) {
            return 0;
        }
        root.getLocationOnScreen(sLoc);
        float density = anchor.getResources().getDisplayMetrics().density;
        float reserve = (sLoc[1] + root.getHeight()) - pillTop
                + LAST_ROW_GAP_DP * density;
        return reserve > 0f ? Math.round(reserve) : 0;
    }

    private static void stretchToBottom(View c, int pageHeight) {
        if (pageHeight <= 0) {
            return;
        }
        int gap = pageHeight - c.getBottom();
        if (gap <= 8) {
            return; // already reaches the bottom
        }
        ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        boolean changed = false;
        if (mlp.bottomMargin != 0) {
            mlp.bottomMargin = 0;
            changed = true;
        }
        if (mlp.height >= 0) {
            mlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            changed = true;
        }
        if (changed) {
            c.setLayoutParams(mlp);
        }
    }

    /** Re-applies a bottom stretch when the host page restores its docked size. */
    private static void keepStretchedToBottom(View c) {
        if (Boolean.TRUE.equals(c.getTag(EXTEND_TAG_KEY))) {
            return;
        }
        c.setTag(EXTEND_TAG_KEY, Boolean.TRUE);
        c.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            ViewGroup parent = v.getParent() instanceof ViewGroup
                    ? (ViewGroup) v.getParent() : null;
            if (parent == null) {
                return;
            }
            dropParentBottomReserve(parent, v);
            ViewGroup grand = parent.getParent() instanceof ViewGroup
                    ? (ViewGroup) parent.getParent() : null;
            if (grand != null) {
                stretchToBottom(parent, grand.getHeight());
            }
            stretchToBottom(v, Math.max(parent.getHeight(),
                    grand == null ? 0 : grand.getHeight()));
        });
    }


    private static void dropParentBottomReserve(ViewGroup parent, View child) {
        int gap = parent.getHeight() - child.getBottom();
        int reserve = parent.getPaddingBottom();
        ViewGroup pager = sPagerRef.get();
        int pageHeight = pager == null ? parent.getHeight() : pager.getHeight();
        if (child.getTop() > 8 || gap <= 8 || reserve <= 0
                || Math.abs(parent.getHeight() - pageHeight) > 8
                || (sBarHeight > 0 && Math.abs(gap - sBarHeight) > 8)
                || Math.abs(reserve - gap) > 8) {
            return;
        }
        int remaining = Math.max(0, reserve - gap);
        parent.setClipToPadding(false);
        parent.setPadding(parent.getPaddingLeft(), parent.getPaddingTop(),
                parent.getPaddingRight(), remaining);
    }

    /**
     * Room the scrollers were last padded with, and the scrollers themselves.
     *
     * <p>Kept so the per-frame path can re-assert padding and clipping without
     * walking the tree again: the apps restore their own view state on layout
     * passes of their own, and a scroller that has quietly gone back to
     * {@code clipToPadding=true} paints its padding band as an opaque strip —
     * the white block that appears over the content partway through a scroll.
     */
    private static final java.util.ArrayList<WeakReference<ViewGroup>> sPaddedScrollers =
            new java.util.ArrayList<>();
    private static int sScrollerPad;
    private static final int MAX_TRACKED_SCROLLERS = 16;

    /** Re-asserts the scroll padding and the clipping flag on every frame. */
    private static void maintainScrollerPadding() {
        int pad = sScrollerPad;
        if (pad <= 0) {
            return;
        }
        for (int i = 0; i < sPaddedScrollers.size(); i++) {
            ViewGroup v = sPaddedScrollers.get(i).get();
            if (v == null) {
                continue;
            }
            if (v.getClipToPadding()) {
                v.setClipToPadding(false);
            }
            if (v.getPaddingBottom() != pad) {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), pad);
            }
        }
    }

    private static void trackPaddedScroller(ViewGroup scroller) {
        for (int i = 0; i < sPaddedScrollers.size(); i++) {
            if (sPaddedScrollers.get(i).get() == scroller) {
                return;
            }
        }
        if (sPaddedScrollers.size() >= MAX_TRACKED_SCROLLERS) {
            sPaddedScrollers.remove(0);
        }
        sPaddedScrollers.add(new WeakReference<>(scroller));
    }

    /**
     * Gives every scrolling view in the subtree room to scroll its last row clear
     * of the floating pill, with {@code clipToPadding=false} so rows still render
     * through the padded band — i.e. behind and below the pill.
     */
    private static void padScrollersBottom(ViewGroup root, int pad, int depth) {
        if (depth > 12) {
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            // ViewPager2 is backed by a RecyclerView, but that RecyclerView is
            // only the horizontal page host. Treating it as the page scroller
            // levels below untouched. Pass through pager RecyclerViews and pad
            // the first actual scrolling container inside each page instead.
            if (isViewPagerRecycler(c)) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            } else if (isScroller(c)) {
                ViewGroup sv = (ViewGroup) c;
                // containers below the page root and still measures it to the
                // old docked bar. Its parent owns that reserve as bottom padding,
                // so remove it before stretching the feed itself. Only touch a
                // top-anchored scroller; title-offset lists keep their origin.
                int parentHeight = root.getHeight();
                if (c.getTop() <= 8 && parentHeight - c.getBottom() > 8) {
                    dropParentBottomReserve(root, c);
                    stretchToBottom(c, parentHeight);
                    keepStretchedToBottom(c);
                }
                // clipToPadding is re-asserted even when the amount already
                // matches: WeChat turns it back on, and with it on the padded
                // band goes blank instead of showing rows through the glass.
                if (sv.getClipToPadding()) {
                    sv.setClipToPadding(false);
                }
                if (sv.getPaddingBottom() != pad) {
                    sv.setPadding(sv.getPaddingLeft(), sv.getPaddingTop(),
                            sv.getPaddingRight(), pad);
                }
                if (pad > 0) {
                    sScrollerPad = pad;
                    trackPaddedScroller(sv);
                }
            } else if (c instanceof ViewGroup) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            }
        }
    }

    /** The RecyclerView used internally by ViewPager2 scrolls between pages. */
    private static boolean isViewPagerRecycler(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            if ("androidx.viewpager2.widget.ViewPager2$RecyclerViewImpl"
                    .equals(k.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognises scrolling containers without compile-time access to androidx,
     * walking the superclass chain so WeChat's own subclasses match too.
     */
    private static boolean isScroller(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        if (v instanceof android.widget.ScrollView
                || v instanceof android.widget.AbsListView) {
            return true;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if ("androidx.recyclerview.widget.RecyclerView".equals(n)
                    || "androidx.core.widget.NestedScrollView".equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Lets the droplet's overflow escape every ancestor up to the content view. */
    private static void unclipAncestors(ViewGroup from) {
        ViewGroup v = from;
        for (int i = 0; i < 12 && v != null; i++) {
            v.setClipChildren(false);
            v.setClipToPadding(false);
            if (v.getId() == android.R.id.content) {
                return;
            }
            android.view.ViewParent p = v.getParent();
            v = p instanceof ViewGroup ? (ViewGroup) p : null;
        }
    }

    /**
     * Width of the widest visible leaf inside a tab column.
     *
     * <p>Measuring the column with an UNSPECIFIED spec is not dependable on its
     * own. WeChat's tab is a RelativeLayout wrapping a chain of MATCH_PARENT
     * children, and a MATCH_PARENT child under an unspecified parent resolves to
     * an UNSPECIFIED spec of size zero — so what comes back is whatever that
     * chain collapses to rather than the label's own width, and the pill ends up
     * hugging a value several times too small. The row is already laid out by the
     * time this runs and its leaves — the icon and the label — carry their real
     * widths, so they are read straight off instead.
     *
     * <p>Only visible leaves count: the unread badge and the red dot sit at
     * INVISIBLE rather than GONE, so they are still laid out, and they are wide
     * enough to stretch the column if allowed to.
     */
    private static int leafContentWidth(View v) {
        if (v.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int widest = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                widest = Math.max(widest, leafContentWidth(g.getChildAt(i)));
            }
            return widest;
        }
        // A MATCH_PARENT leaf is only ever as wide as the column it was handed,
        // it fills the whole 305px column while the glyph in it is about 55px —
        // and counting it would size every column to the screen quarter it
        // already had, which is the opposite of hugging the content.
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            return 0;
        }
        return v.getWidth();
    }


    private static int hugContentWidth(ViewGroup tabRow, float density) {
        if (tabRow == null || tabRow.getChildCount() == 0) {
            return 0;
        }
        int childCount = tabRow.getChildCount();
        int count = 0;
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int measured = 0;
        int leaf = 0;
        int slot = 0;
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            count++;
            tab.measure(unspecified, unspecified);
            measured = Math.max(measured, tab.getMeasuredWidth());
            leaf = Math.max(leaf, leafContentWidth(tab));
            slot = Math.max(slot, tab.getWidth());
        }
        if (count == 0) {
            return 0;
        }
        // An unbounded measure that comes back wider than the column the tab is
        // already laid out in has not measured content at all — it has fallen
        // MATCH_PARENT child against UNSPECIFIED is undefined and its
        // RelativeLayout answers with something unrelated. WeChat's 105px for
        // the same 305px column is a real content width and is kept.
        if (slot > 0 && measured > slot) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "unbounded measure returned " + measured + " for a " + slot
                            + "px column; using the leaf width instead");
            measured = 0;
        }
        // Whichever reads wider. Each can come back short on its own — the
        // measure when the layout will not measure unbounded, the leaves when
        // the widest thing in the tab sizes itself to the column — so the
        // maximum is the one that holds for both apps.
        int widest = Math.max(measured, leaf);
        if (widest <= 0) {
            return 0;
        }
        // WeChat's labels are narrower than KernelSU's, so hugging them exactly
        // gives a cramped 46%-wide bar. Matching KernelSU's proportions means
        // giving each column the same generous breathing room it uses (~32dp per
        // side), then capping so the pill still clears the screen edges.
        int pad = Math.round(density * GlassStyle.PANEL_PADDING_DP);
        int screen = tabRow.getRootView().getWidth();
        if (screen <= 0) screen = tabRow.getResources().getDisplayMetrics().widthPixels;
        int tabWidth = GlassStyle.tabWidth(widest, count, screen, density);
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            if (lp == null) {
                continue;
            }
            lp.width = tabWidth;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) lp).weight = 0f;
            }
            tab.setLayoutParams(lp);
        }
        // Horizontal inset only: the bar's height is WeChat's own and adding
        // vertical padding pushes the tabs out through the bottom of the pill.
        tabRow.setPadding(pad, pad, pad, pad);
        int total = tabWidth * count + pad * 2;
        LiquidGlassModule.log(android.util.Log.INFO,
                "tab row hugged: content=" + widest
                        + " (measured=" + measured + " leaf=" + leaf + ")"
                        + " tabWidth=" + tabWidth
                        + " total=" + total + " screen=" + screen);
        return total;
    }

    /**
     * Keeps the glass and shadow travelling with WeChat's own bar.
     *
     * <p>WeChat slides the bar out of the way with {@code translationY} — that is
     * how it gets out of the way for the mini-program panel — and fades it with
     * alpha. Since only the bar itself was reparented into the host, those
     * properties would otherwise move the tabs while the glass and its shadow
     * stayed put. Mirroring them onto our own layers keeps the pill whole, and
     * reading rather than overwriting them leaves WeChat's animator alone.
     */
    /**
     * How much further than WeChat the bar has to travel to leave the screen,
     * as a fraction of WeChat's own travel. Zero once the pill already clears.
     */
    private static float hideShortfall(LiquidGlassHostLayout host, View tabView) {
        float travel = tabView.getHeight();
        if (travel <= 0f) {
            return 0f;
        }
        host.getLocationOnScreen(sLoc);
        // Undo the offset this very method applied, so the reference stays put.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        root.getLocationOnScreen(sLoc);
        float need = sLoc[1] + root.getHeight() - pillTop;
        return Math.max(0f, need / travel - 1f);
    }

    private static void followBarOffset(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return;
        }
        // Both apps repaint the bar's own background when the theme changes —
        // drawable and would bury the glass under it. Nothing announces that,
        // so it is re-checked here; the common case is a null field read.
        if (tabView.getBackground() != null) {
            stripSolidBackgrounds(tabView);
        }
        float ty = tabView.getTranslationY();
        float alpha = tabView.getAlpha();
        boolean gone = tabView.getVisibility() != View.VISIBLE;

        // WeChat slides its bar down by exactly its own height, which was enough
        // to clear the bottom of the screen when the bar sat flush against it.
        // The pill floats above that, so the same travel leaves a slice of it
        // still showing when WeChat gives up and hides the bar outright — it
        // vanishes mid-slide. The shortfall is added to the host rather than to
        // the glass: the tab icons are carried by WeChat's own translation, and
        // moving the glass alone would slide it out from under them.
        // Only worth computing while the bar is actually moving.
        host.setTranslationY(ty == 0f ? 0f : hideShortfall(host, tabView) * ty);

        View glass = sGlassRef.get();
        if (glass != null && glass.getTranslationY() != ty) {
            glass.setTranslationY(ty);
        }
        View droplet = sDropletRef.get();
        if (droplet != null) {
            droplet.setTranslationY(sDropletBaseY + ty);
            droplet.setAlpha(gone ? 0f : alpha);
        }
        if (glass != null) {
            glass.setAlpha(gone ? 0f : alpha);
        }
        host.setShadowOffsetY(gone ? Float.MAX_VALUE : ty, alpha);
    }

    /** Puts a layer back to GONE if the app re-showed it; reports once. */
    private static void holdHidden(View v, boolean report) {
        if (v == null || v.getVisibility() == View.GONE) {
            return;
        }
        v.setVisibility(View.GONE);
        if (report && !sBlurRelit) {
            sBlurRelit = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "the app re-lit its own blur layer; holding it hidden");
        }
    }


    private static void holdOwnBarChromeHidden(LiquidGlassHostLayout host) {
        android.view.ViewParent rawParent = host.getParent();
        HostApp app = LiquidGlassModule.app();
        if (rawParent instanceof ViewGroup
                && app != null && app.hiddenSiblings.length > 0) {
            ViewGroup parent = (ViewGroup) rawParent;
            View tabView = sTabViewRef.get();
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                    sBlurLayerRef = new WeakReference<>(c);
                    holdHidden(c, true);
                }
            }
        } else {
            holdHidden(sBlurLayerRef.get(), true);
        }
        holdHidden(sHairlineRef.get(), false);
    }

    /**
     * Tracks the selected tab and the visible pager page once per frame.
     *
     * <p>Both have to be polled rather than hooked: {@code setTo(int)} does not
     * fire on tab taps, and the pager scrolls between fixed child offsets instead
     * of restacking them, so nothing notifies us when the page under the glass
     * changes.
     */
    /**
     * Holds the navigation bar transparent.
     *
     * <p>WeChat's chat screen opens inside LauncherUI and paints the navigation
     * bar opaque on its way in, then leaves it that way — which puts a solid
     * strip back over the content the pill floats above. Nothing tells us when
     * that happens (no Activity change, no relayout), so it is simply checked
     * whenever the bar draws; the read is a field access and the write only
     * happens when WeChat has actually changed it.
     */
    private static WeakReference<View> sNavBgRef = new WeakReference<>(null);
    private static int sNavBgId = -1;

    /** The DecorView's navigation-bar backdrop, looked up by its framework id. */
    private static View navBarBackground(View decor) {
        View v = sNavBgRef.get();
        if (v != null && v.getParent() != null
                && v.getRootView() == decor.getRootView()) {
            return v;
        }
        if (sNavBgId == -1) {
            sNavBgId = decor.getResources().getIdentifier(
                    "navigationBarBackground", "id", "android");
        }
        if (sNavBgId == 0) {
            return null;
        }
        v = decor.findViewById(sNavBgId);
        sNavBgRef = new WeakReference<>(v);
        return v;
    }

    /**
     * Keeps the navigation bar from covering the content.
     *
     * <p>WeChat's chat screen opens inside LauncherUI — no Activity change, no
     * window focus change — and paints the navigation bar opaque on its way in,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: the system bar drawn over content that already reaches the
     * edge, not a layout gap.
     *
     * <p>Setting the colour back is no use here. On HyperOS the value reads
     * straight back as WeChat's, so the write never lands. The bar's backdrop is
     * an ordinary View inside the DecorView though, and hiding that is entirely
     * ours to do — and it holds, because the DecorView has to run a draw pass to
     * show it again, and this runs first in every one of them.
     */
    private static void keepNavBarClear() {
        LiquidGlassModule.app().adapter.maintainWindow();
    }

    static void clearNavigationBar() {
        Activity a = sActivityRef.get();
        if (a == null) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        View navBg = navBarBackground(decor);
        if (navBg != null && navBg.getVisibility() != View.GONE) {
            navBg.setVisibility(View.GONE);
        }
        // Checked separately: losing this flag shrinks the window back above the
        // gesture bar, which is a real gap rather than something drawn over.
        int vis = decor.getSystemUiVisibility();
        if ((vis & View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0) {
            decor.setSystemUiVisibility(
                    vis | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }


    private static int tabStructureSignature(ViewGroup tabRow) {
        if (tabRow == null) {
            return 0;
        }
        int signature = System.identityHashCode(tabRow);
        signature = signature * 31 + tabRow.getVisibility();
        signature = signature * 31 + tabRow.getChildCount();
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            signature = signature * 31 + System.identityHashCode(tab);
            signature = signature * 31 + tab.getVisibility();
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            signature = signature * 31 + System.identityHashCode(lp);
            boolean equalWeight = lp != null && lp.width == 0;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                equalWeight |= ((android.widget.LinearLayout.LayoutParams) lp).weight != 0f;
            }
            signature = signature * 31 + (equalWeight ? 1 : 0);
            if (tab instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) tab;
                signature = signature * 31 + group.getChildCount();
                for (int j = 0; j < group.getChildCount(); j++) {
                    View child = group.getChildAt(j);
                    signature = signature * 31 + System.identityHashCode(child);
                    signature = signature * 31 + child.getVisibility();
                }
            }
        }
        return signature;
    }


    private static boolean scheduleTabStructureRefreshIfNeeded(
            LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (!(tabView instanceof ViewGroup)) {
            return false;
        }
        ViewGroup current = TabBarBridge.findTabRow((ViewGroup) tabView);
        if (current == null || current.getVisibility() != View.VISIBLE
                || TabBarBridge.tabCount(current) == 0) {
            // the setting. Keep the old binding untouched and retry next frame
            // — but not indefinitely: a row that never comes back would
            // otherwise hold this method's `true` forever, and with it the
            // selection watcher and the droplet.
            ViewGroup bound = sTabRowRef.get();
            boolean boundUsable = bound != null && bound.isAttachedToWindow()
                    && bound.getVisibility() == View.VISIBLE
                    && TabBarBridge.tabCount(bound) > 0;
            if (boundUsable) {
                sTabRowEmptySinceMs = 0L;
                return true;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (sTabRowEmptySinceMs == 0L) {
                sTabRowEmptySinceMs = now;
            }
            return now - sTabRowEmptySinceMs < TAB_ROW_EMPTY_GIVE_UP_MS;
        }
        sTabRowEmptySinceMs = 0L;
        if (sTabStructureRefreshPosted) {
            return true;
        }
        int signature = tabStructureSignature(current);
        if (current == sTabRowRef.get() && signature == sTabStructureSignature) {
            return false;
        }
        sTabStructureRefreshPosted = true;
        host.post(() -> refreshTabStructure(host));
        return true;
    }

    /** Re-hugs and rebinds a bottom bar after its tab topology changes. */
    private static void refreshTabStructure(LiquidGlassHostLayout host) {
        try {
            if (host != sHostRef.get() || host.getParent() == null) {
                return;
            }
            View tabView = sTabViewRef.get();
            if (!(tabView instanceof ViewGroup)) {
                return;
            }
            ViewGroup tabRow = TabBarBridge.findTabRow((ViewGroup) tabView);
            if (tabRow == null || tabRow.getVisibility() != View.VISIBLE
                    || TabBarBridge.tabCount(tabRow) == 0) {
                return;
            }

            // equal-weight widths and docked height back with them. Apply the
            // same treatment as the initial install, then resize the already-live
            // host around the row.
            stripSolidBackgrounds(tabView);
            dropNavReserve(tabView);
            ViewGroup.LayoutParams tabLp = tabView.getLayoutParams();
            if (tabLp != null && sBarHeight > 0 && tabLp.height != sBarHeight) {
                tabLp.height = sBarHeight;
                tabView.setLayoutParams(tabLp);
            }
            float density = host.getResources().getDisplayMetrics().density;
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth <= 0) {
                return; // not laid out yet; leave the old signature so we retry
            }
            ViewGroup.LayoutParams lp = host.getLayoutParams();
            int desired = barWidth + host.shadowPad() * 2;
            if (lp != null && lp.width != desired) {
                lp.width = desired;
                host.setLayoutParams(lp);
            }

            sTabRowRef = new WeakReference<>(tabRow);
            sTabStructureSignature = tabStructureSignature(tabRow);
            View droplet = sDropletRef.get();
            if (droplet instanceof DropletPanel) {
                ((DropletPanel) droplet).setTabRow(tabRow);
            }
            if (sDrag != null) {
                sDrag.setTabRow(tabRow);
            }

            // The next pre-draw runs after the requested layout and snaps the
            // droplet to the selected tab using the new geometry.
            sLastIndex = -1;
            tabRow.requestLayout();
            tabView.requestLayout();
            host.requestLayout();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "tab structure rebound: row=" + tabRow.getClass().getName()
                            + " children=" + tabRow.getChildCount()
                            + " hostWidth=" + (lp == null ? 0 : lp.width)
                            + " barHeight=" + sBarHeight);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("tab structure refresh failed", t);
        } finally {
            sTabStructureRefreshPosted = false;
        }
    }


    private static boolean restoreBarContentHeight(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return false;
        }
        boolean changed = dropNavReserve(tabView) > 0;
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        if (lp != null && sBarHeight > 0 && lp.height != sBarHeight) {
            lp.height = sBarHeight;
            tabView.setLayoutParams(lp);
            changed = true;
        }
        if (changed) {
            tabView.requestLayout();
            host.requestLayout();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "bar content height restored after host relayout: "
                            + sBarHeight);
        }
        return changed;
    }

    private static void installSelectionWatcher(LiquidGlassHostLayout host) {
        host.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (host != sHostRef.get() || !host.isAttachedToWindow()) {
                return true;
            }
            // First, and on its own: anything below must not be able to stop the
            // navigation bar being held clear.
            try {
                keepNavBarClear();
                syncHostBottomInset(host, sNavigationInset);
                dropBottomFrostAll();
                holdOwnBarChromeHidden(host);
            } catch (Throwable t) {
                // Once only: this sits on a per-frame path.
                if (!sKeepFailed) {
                    sKeepFailed = true;
                    LiquidGlassModule.logErr("keepNavBarClear failed", t);
                }
            }
            try {
                if (restoreBarContentHeight(host)) {
                    return true;
                }
                followBarOffset(host);
                View bar = sTabViewRef.get();
                if (bar instanceof ViewGroup) LiquidGlassModule.app().adapter.maintain((ViewGroup) bar, host);
                if (scheduleTabStructureRefreshIfNeeded(host)) {
                    return true;
                }
                // Kept honest per frame: both apps undo parts of the page
                // surgery on layout passes of their own, and a scroller that
                // has gone back to clipping its padding paints an opaque band
                // over the content it is scrolling.
                maintainScrollerPadding();
                sPageExtent.request();
                maintainDropletGeometry();
                ViewGroup tabRow = sTabRowRef.get();
                int sel = visibleTabSelection(tabRow);
                if (sel >= 0 && sel != sLastIndex) {
                    boolean first = sLastIndex < 0;
                    sLastIndex = sel;
                    syncDropletSize(sel);
                    if (sDrag != null) {
                        // KernelSU animates programmatic switches the same way as
                        // drags: press, travel, release.
                        sDrag.animateToIndex(sel, first);
                    }
                    ViewGroup pgr = sPagerRef.get();
                    if (pgr != null) {
                        pgr.post(() -> extendPagesToBottom(pgr));
                    }
                }
            } catch (Throwable ignored) {
            }
            return true;
        });
    }

    /**
     * How long a page/bar disagreement has to last before the page is believed:
     * long enough that the ordinary lag between a page scroll and the bar's own
     * selection never reaches it.
     */
    private static final long PAGE_BAR_SETTLE_MS = 250L;
    /** Uptime at which the current disagreement began, or 0 while they agree. */
    private static long sPageBarDisagreeSinceMs;
    private static boolean sPageWonLogged;
    /** Draws the frame that settles a disagreement, in case nothing else would. */
    private static final Runnable sPageBarSettle = () -> {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host != null && sPageBarDisagreeSinceMs != 0L) {
            host.invalidate();
        }
    };

    /**
     * Selected slot, with the page actually on screen given the last word.
     *
     * <p>Both apps occasionally leave the bar behind the pager. The reported
     * case is WeChat's wallet: coming back from it the pager is on the chat
     * list while the tab views still hold 我, so a droplet that only ever reads
     * the bar highlights a page nobody is looking at (#9). The page wins, but
     * only once the disagreement has held for a while, so a bar that is merely
     * a step behind still drives the animation.
     *
     * <p>Timed on the clock, not counted in frames: frames stop coming once the
     * screen settles, and the wallet case settles right there.
     */
    private static int visibleTabSelection(ViewGroup tabRow) {
        int selected = TabBarBridge.selectedIndex(tabRow);
        int page = TabBarBridge.pageSlot(sPagerRef.get(), tabRow);
        LiquidGlassHostLayout host = sHostRef.get();
        if (page < 0 || page == selected) {
            if (sPageBarDisagreeSinceMs != 0L && host != null) {
                host.removeCallbacks(sPageBarSettle);
            }
            sPageBarDisagreeSinceMs = 0L;
            return selected;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (sPageBarDisagreeSinceMs == 0L) {
            sPageBarDisagreeSinceMs = now;
            if (host != null) {
                host.postDelayed(sPageBarSettle, PAGE_BAR_SETTLE_MS + 16L);
            }
            return selected;
        }
        if (now - sPageBarDisagreeSinceMs < PAGE_BAR_SETTLE_MS) {
            return selected;
        }
        if (!sPageWonLogged) {
            sPageWonLogged = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "bar still reports tab " + selected + " while page " + page
                            + " is on screen; following the page");
        }
        return page;
    }

    /**
     * Re-runs the page stretch on a slow timer, asked for from every frame.
     *
     * <p>The apps re-apply their own page metrics whenever they relayout, and
     * they do it silently — no layout change on any view we listen to. When
     * that happens the pages stop short again and the band the bar vacated
     * shows the window's own background: the white strip under the pill, and
     * the grey-and-white patchwork at the end of a scrolled list (#6).
     *
     * <p>Cheap by construction: every step inside {@link #extendPagesToBottom}
     * bails out on its own when its gap is already closed, so a run costs a
     * short walk of the pages down to their scrollers — until something really
     * did move, and then it is repaired within half a second instead of at the
     * next tab switch. The last run lands after the frames stop, which is
     * exactly when a relayout that put the gap back has just finished drawing.
     */
    private static final ClockThrottle sPageExtent = new ClockThrottle(500L, () -> {
        ViewGroup pager = sPagerRef.get();
        if (pager != null && pager.isAttachedToWindow()) {
            extendPagesToBottom(pager);
        }
    });

    /**
     * The sibling the glass refracts. WeChat puts the pager and the tab bar under
     * the same parent, so the backdrop is whichever sibling is not the bar itself
     * and actually covers the screen.
     */
    private static ViewGroup findBackdrop(ViewGroup parent, View tabView) {
        ViewGroup best = null;
        int bestArea = 0;
        HostApp app = LiquidGlassModule.app();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || !(c instanceof ViewGroup)
                    || c.getVisibility() != View.VISIBLE
                    || (app != null && app.isHiddenSibling(c.getClass().getName()))) {
                continue;
            }
            int area = c.getWidth() * c.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = (ViewGroup) c;
            }
        }
        return best;
    }

    /**
     * WeChat paints the bar's opaque colour on the inner LinearLayout
     * ({@code E.setBackgroundColor(...)}). Only flat colour fills are removed —
     * anything else (badge shapes, ripples) is left alone.
     */
    private static void stripSolidBackgrounds(View v) {
        // The bar and its row are drawn by the glass now, so clear their
        // backgrounds outright rather than only flat fills: WeChat paints the
        // bar's hairline top divider through a non-ColorDrawable background, and
        // leaving it in draws a stray line across the top of the pill that stops
        // short at the row's padding edge.
        if (v.getBackground() != null) {
            v.setBackground(null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            // Down to the tab columns themselves: each carries a top hairline,
            // and the four of them line up into a divider spanning the row's
            // content box (214..1006 px) right across the pill's top edge.
            // Their children keep theirs (badges, icon states).
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c.getBackground() != null) {
                    c.setBackground(null);
                }
                if (c instanceof ViewGroup) {
                    ViewGroup row = (ViewGroup) c;
                    for (int j = 0; j < row.getChildCount(); j++) {
                        View tab = row.getChildAt(j);
                        if (tab.getBackground() != null) {
                            tab.setBackground(null);
                        }
                    }
                }
            }
        }
    }


    private static void hideOwnBlurLayers(ViewGroup parent, View tabView) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                sBlurLayerRef = new WeakReference<>(c);
                if (c.getVisibility() != View.GONE) {
                    c.setVisibility(View.GONE);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hid the app's own blur layer: "
                                    + c.getClass().getName());
                }
            }
        }
    }

    /**
     * Puts the app's blur layer back, for the path where the pill never
     * arrived. Only ever undoes {@link #hideOwnBlurLayers}, which is why the
     * restored state is unconditionally VISIBLE: nothing else is touched.
     */
    private static void showOwnBlurLayers(ViewGroup parent) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c.getVisibility() == View.GONE
                    && app.isHiddenSibling(c.getClass().getName())) {
                c.setVisibility(View.VISIBLE);
            }
        }
    }


    private static void hideBarHairline(ViewGroup parent, View tabView) {
        float density = parent.getResources().getDisplayMetrics().density;
        int maxThickness = Math.max(2, Math.round(density * 1.5f));
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || c.getVisibility() != View.VISIBLE
                    || c instanceof ViewGroup) {
                continue;
            }
            if (c.getHeight() > 0 && c.getHeight() <= maxThickness
                    && c.getWidth() >= parent.getWidth() * 0.9f
                    && c.getBackground() != null) {
                c.setVisibility(View.GONE);
                sHairlineRef = new WeakReference<>(c);
                LiquidGlassModule.log(android.util.Log.INFO,
                        "hid the bar's " + c.getHeight() + "px hairline");
            }
        }
    }





    /**
     * Bar height that leaves the same gap above and below the tabs' content.
     *
     * <p>The bar's own height is not trustworthy under edge-to-edge. On some
     * devices 8.0.72 sizes the bar to cover the navigation inset without putting
     * it anywhere {@link #dropNavReserve} can find it, so the bar measures some
     * 20dp taller than what it actually draws. The pill inherits that as a band
     * of dead space along its bottom edge: the tabs sit against the top with a
     * gap underneath — measured at +72px and +53px on the two devices in
     * issue #1, against −3px on a third whose reserve is a single pixel and
     * which therefore cannot reproduce it at all.
     *
     * <p>So it is measured off the content instead. Whatever gap the bar leaves
     * above its icons is the one it means to have; mirroring that below gives
     * the height the bar would have had without the inset, no matter which route
     * the inset took to get in.
     *
     * <p>Only ever shrinks the bar. If the content cannot be located, or the
     * symmetric height would be taller than what the bar reports, the bar's own
     * height stands.
     */
    private static int contentBarHeight(ViewGroup tabRow, int fallback) {
        if (tabRow == null || fallback <= 0) {
            return fallback;
        }
        int top = Integer.MAX_VALUE;
        int bottom = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            int[] b = {Integer.MAX_VALUE, 0};
            leafBounds(tab, 0, b);
            if (b[0] < b[1]) {
                int base = tabRow.getTop() + tab.getTop();
                top = Math.min(top, base + b[0]);
                bottom = Math.max(bottom, base + b[1]);
            }
        }
        if (top == Integer.MAX_VALUE || bottom <= top) {
            return fallback;
        }
        int symmetric = bottom + top;
        LiquidGlassModule.log(android.util.Log.INFO,
                "bar height: content " + top + ".." + bottom
                        + " -> " + symmetric + " (bar reports " + fallback + ")");
        return symmetric > 0 && symmetric < fallback ? symmetric : fallback;
    }

    /** Vertical extent of a tab's drawn content, relative to the tab column. */
    private static void leafBounds(View v, int offset, int[] out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                leafBounds(c, offset + c.getTop(), out);
            }
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        out[0] = Math.min(out[0], offset);
        out[1] = Math.max(out[1], offset + v.getHeight());
    }

    private static int dropNavReserve(View tabView) {
        int reserve = tabView.getPaddingBottom();
        if (reserve <= 0) {
            return 0;
        }
        tabView.setPadding(tabView.getPaddingLeft(), tabView.getPaddingTop(),
                tabView.getPaddingRight(), 0);
        LiquidGlassModule.log(android.util.Log.INFO,
                "dropped the bar's " + reserve + "px navigation reserve");
        return reserve;
    }

    private static final int EDGE_TAG_KEY = 0x7F5A0002;

    /**
     * Lets the pages draw under the gesture bar.
     *
     * <p>WeChat's window stops at the navigation bar, so once the tab bar is
     * lifted off the bottom there is a dead strip below it that nothing ever
     * paints. Asking for the hide-navigation layout grows the window to the
     * whole screen; the navigation inset is then stripped on its way down the
     * tree, because otherwise WeChat's own containers just pad the same gap
     * straight back in.
     *
     * @return whether the window was actually grown
     */
    private static boolean extendUnderNavBar(Context ctx) {
        return LiquidGlassModule.app().adapter.configureWindow(ctx);
    }

    static boolean enableEdgeToEdge(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;
        }
        try {
            Activity activity = activityOf(ctx);
            if (activity == null) {
                activity = sActivityRef.get();
            }
            if (activity == null) {
                LiquidGlassModule.log(android.util.Log.WARN,
                        "no Activity for the window, bottom strip stays blank");
                return false;
            }
            android.view.Window w = activity.getWindow();
            View decor = w.getDecorView();
            boolean first = !Boolean.TRUE.equals(decor.getTag(EDGE_TAG_KEY));

            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
            android.view.WindowInsetsController ctrl = w.getInsetsController();
            if (ctrl != null) {
                // The gesture pill now sits on WeChat's own content rather than
                // on a system background, so it has to contrast with that.
                int light = android.view.WindowInsetsController
                        .APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(isNight(ctx) ? 0 : light, light);
            }
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (first) {
                decor.setTag(EDGE_TAG_KEY, Boolean.TRUE);
                decor.setOnApplyWindowInsetsListener((v, insets) -> {
                    int inset = rememberNavigationInset(insets);
                    LiquidGlassHostLayout host = sHostRef.get();
                    if (host != null
                            && host.getRootView() == v.getRootView()) {
                        syncHostBottomInset(host, inset);
                    }
                    WindowInsets stripped = new WindowInsets.Builder(insets)
                            .setInsets(WindowInsets.Type.navigationBars(),
                                    android.graphics.Insets.NONE)
                            .build();
                    return v.onApplyWindowInsets(stripped);
                });
                LiquidGlassModule.log(android.util.Log.INFO,
                        "window extended under the navigation bar, inset="
                                + navInset(decor));
            }
            decor.requestApplyInsets();
            return true;
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not extend under the nav bar", t);
            return false;
        }
    }

    /**
     * Keeps the bottom strip filled without guessing at timings.
     *
     * <p>Coming back from a chat re-runs WeChat's own window setup, and it does
     * so on its own schedule — a delayed one-shot after resume sometimes lands
     * before WeChat has finished handing the navigation inset back. Watching the
     * pager's layout and the window's focus catches it whenever it happens.
     */
    private static void watchBottomInset(LiquidGlassHostLayout host, ViewGroup backdrop) {
        backdrop.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            extendPagesToBottom(backdrop);
            // Catches the transition frame itself rather than waiting for the
            // bar's next draw, so the strip never flashes on the way back.
            keepNavBarClear();
        });
        host.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus) {
                return;
            }
            Activity a = sActivityRef.get();
            if (a != null) {
                reassertBottom(a);
            }
        });
    }

    /**
     * Re-applies the edge-to-edge window state and re-stretches the pages.
     *
     * <p>Runs on every resume of an already-installed window. WeChat restores
     * its own system-ui flags when a secondary screen closes, which hands the
     * navigation inset straight back to its containers.
     */
    private static void reassertBottom(Activity activity) {
        if (!extendUnderNavBar(activity)) {
            return;
        }
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        pager.post(() -> extendPagesToBottom(pager));
        // The insets land a frame or two after the window is re-shown, and the
        // pages can only be stretched once the room is actually there.
        pager.postDelayed(() -> extendPagesToBottom(pager), 400L);
    }



    /**
     * Stops WeChat repainting the navigation bar over the content.
     *
     * <p>The chat screen opens inside LauncherUI — no Activity change, no window
     * focus change — and on its way in it paints the navigation bar opaque,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: not a layout gap, the system bar itself drawn over content
     * that already reaches the edge.
     *
     * <p>Correcting it after the fact is not enough. There is nothing to react
     * to, and a per-frame check only runs while something is being drawn — once
     * the list settles, no frames, no correction. So the call itself is
     * swallowed for this one window instead.
     */

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static int navInset(View anchor) {
        try {
            WindowInsets insets = anchor.getRootWindowInsets();
            return insets == null ? 0 : navigationInset(insets);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Returns the last non-zero system inset once our listener has stripped it. */
    private static int rememberNavigationInset(View anchor) {
        int inset = navInset(anchor);
        if (inset > 0) {
            sNavigationInset = inset;
        }
        return inset > 0 ? inset : sNavigationInset;
    }

    private static int rememberNavigationInset(WindowInsets insets) {
        int inset = navigationInset(insets);
        // Theme recreation sends a transient zero-inset dispatch before the
        // real navigation-bar frame. Do not let that one frame pull an already
        // anchored pill down; the following non-zero dispatch refreshes the
        // cache normally. A process that genuinely starts at zero still keeps
        // zero until it sees a real bar.
        if (inset > 0 || sNavigationInset == 0) {
            sNavigationInset = inset;
        }
        return inset > 0 ? inset : sNavigationInset;
    }

    private static int navigationInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /** Keeps an already-installed pill anchored when system insets change. */
    private static void syncHostBottomInset(LiquidGlassHostLayout host, int inset) {
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        float density = host.getResources().getDisplayMetrics().density;
        int desired = GlassStyle.bottomMargin(inset,
                parentBottomGap((View) host.getParent()), density) - host.shadowPad();
        if (lp.bottomMargin == desired) {
            return;
        }
        lp.bottomMargin = desired;
        host.setLayoutParams(lp);
        LiquidGlassModule.log(android.util.Log.INFO,
                "pill bottom anchor refreshed: inset=" + inset
                        + " margin=" + desired);
    }

    private static int parentBottomGap(View parent) {
        int[] parentPos = new int[2];
        int[] rootPos = new int[2];
        View root = parent.getRootView();
        parent.getLocationOnScreen(parentPos);
        root.getLocationOnScreen(rootPos);
        return Math.max(0, rootPos[1] + root.getHeight() - parentPos[1] - parent.getHeight());
    }

    private static int maximumLabelHeight(View view) {
        if (view == null || view.getVisibility() == View.GONE) return 0;
        if (view instanceof android.widget.TextView) {
            return ((android.widget.TextView) view).getLineHeight();
        }
        int height = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                height = Math.max(height, maximumLabelHeight(group.getChildAt(i)));
            }
        }
        return height;
    }

    /* ---------------- renderer ---------------- */

    private static boolean isNight(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static void attachRenderer(Context ctx, LiquidGlassHostLayout host,
                                       ViewGroup backdrop, float density) {
        // RenderNode capture and RenderEffect blur are Android 12; below that
        // the host's own software frost is all there is.
        if (Build.VERSION.SDK_INT < LiquidGlassPanel.MIN_SDK) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "SDK < " + LiquidGlassPanel.MIN_SDK
                            + ", staying on the legacy frost path");
            return;
        }
        try {
            boolean night = isNight(ctx);

            // The glass surface is a direct port of KernelSU's effect stack,
            // see LiquidGlassPanel.
            final LiquidGlassPanel glass =
                    new LiquidGlassPanel(ctx, backdrop, density, night);
            host.addView(glass, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // The droplet goes on top of the tabs, not under them: it refracts a
            // separately drawn, enlarged copy of the tab row, and that refracted
            // copy is what should be visible inside it — same as KernelSU.
            final DropletPanel droplet = new DropletPanel(
                    ctx, backdrop, sTabRowRef.get(), density, night);
            droplet.setVisibility(View.INVISIBLE);
            host.addView(droplet, new FrameLayout.LayoutParams(0, 0,
                    android.view.Gravity.TOP | android.view.Gravity.START));
            droplet.setPill(glass);
            sDropletRef = new WeakReference<>(droplet);
            // The droplet scales past the pill's bounds while held (78/56). Both
            // flags matter: FrameLayout defaults clipToPadding to true, and the
            // host carries 14dp of shadow padding — that alone was shearing off
            // exactly the overflow we wanted to show.
            host.setClipChildren(false);
            host.setClipToPadding(false);
            sGlassRef = new WeakReference<>(glass);

            host.setGlassTuner(new LiquidGlassHostLayout.GlassTuner() {
                @Override
                public void onSize(int w, int h, float cornerRadius) {
                    glass.invalidate();
                }

                @Override
                public void onTheme(boolean dark) {
                    glass.setTheme(dark);
                    droplet.setTheme(dark);
                }
            });

            ViewGroup tabRow = sTabRowRef.get();
            if (tabRow != null) {
                sDrag = new DropletDragController(droplet, tabRow, density, night);
                sDrag.setPill(glass);
                sDrag.setHost(host);
                host.setDragHandler(sDrag);
            }

            LiquidGlassModule.log(android.util.Log.INFO,
                    "renderer=KernelSU-style glass (saturation+blur"
                            + (glass.hasLens() ? "+SDF refraction)" : ", no lens)")
                            + " drag=" + (tabRow != null));
        } catch (Throwable t) {
            LiquidGlassModule.logErr("glass renderer unavailable", t);
        }
    }


    /* ---------------- droplet ---------------- */

    /** Resolves hook/getter indices to the row's current visible layout slot. */
    private static int resolveTabSlot(int appIndex) {
        ViewGroup tabRow = sTabRowRef.get();
        int selected = TabBarBridge.selectedIndex(tabRow);
        return selected >= 0 ? selected : TabBarBridge.slotForIndex(tabRow, appIndex);
    }


    static void onTabChanged(View tabView, int index) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host != null && !host.isAttachedToWindow()) {
            resetState();
            host = null;
        }
        if (host == null || host.getParent() == null || tabView.getParent() != host) {
            // Not ours (yet). Either the first call of this process, or a fresh
            // LauncherUI instance after the old one was destroyed.
            if (tabView instanceof ViewGroup && tabView.getParent() != null) {
                tabView.post(() -> {
                    try {
                        install((ViewGroup) tabView);
                        syncDropletSize(resolveTabSlot(index));
                    } catch (Throwable t) {
                        LiquidGlassModule.logErr("install from setTo failed", t);
                    }
                });
            }
            return;
        }
        LiquidGlassHostLayout installedHost = host;
        installedHost.post(() -> {
            if (scheduleTabStructureRefreshIfNeeded(installedHost)) {
                return;
            }
            int slot = resolveTabSlot(index);
            if (slot < 0) {
                return;
            }
            syncDropletSize(slot);
            if (sDrag != null) {
                sDrag.animateToIndex(slot, false);
            }
        });
    }


    private static void syncDropletSize(int index) {
        try {
            View droplet = sDropletRef.get();
            ViewGroup tabRow = sTabRowRef.get();
            LiquidGlassHostLayout host = sHostRef.get();
            if (droplet == null || tabRow == null || host == null || index < 0) {
                return;
            }
            View tab = TabBarBridge.tabAt(tabRow, index);
            if (tab == null || tab.getWidth() == 0) {
                return;
            }
            // The pill's inner box. Everything outside it is our own shadow
            // padding, which the droplet must neither include nor overhang.
            int pillTop = host.getPaddingTop();
            int pillBottom = host.getHeight() - host.getPaddingBottom();
            if (pillBottom <= pillTop || !ViewGeom.positionIn(tab, host, sPos)) {
                return;
            }
            float density = host.getResources().getDisplayMetrics().density;
            // KernelSU sizes the droplet to the full tab column: width = tabWidth,
            // height = bar height - 2 * 4dp padding.
            int inset = Math.round(density * GlassStyle.PANEL_PADDING_DP);
            int w = tab.getWidth();
            // at the old docked height and lets the bar clip them — so the
            // droplet is held inside the pill rather than hanging out of its
            // bottom edge.
            int h = Math.min(tab.getHeight(), pillBottom - pillTop - inset * 2);
            if (w <= 0 || h <= 0) {
                return;
            }
            int top = Math.max(sPos[1], pillTop + inset);
            if (top + h > pillBottom) {
                top = pillBottom - h;
            }
            if (top < pillTop) {
                top = pillTop;
            }
            ViewGroup.LayoutParams lp = droplet.getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                droplet.setLayoutParams(lp);
            }
            sDropletBaseY = top - pillTop;
            droplet.setTranslationY(sDropletBaseY);
            droplet.setVisibility(View.VISIBLE);
            sDropletGeometryKey = dropletGeometryKey(host, tab);
            if (!sDropletLogged) {
                sDropletLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "droplet placed: tab=" + sPos[0] + "," + sPos[1]
                                + " size=" + w + "x" + h
                                + " pill=" + pillTop + ".." + pillBottom
                                + " hostPad=" + host.getPaddingLeft()
                                + "," + host.getPaddingTop()
                                + " tabH=" + tab.getHeight());
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("droplet sizing failed", t);
        }
    }

    /** Fingerprint of the bar geometry the droplet was last sized against. */
    private static int dropletGeometryKey(LiquidGlassHostLayout host, View tab) {
        int key = tab.getWidth();
        key = key * 131 + tab.getHeight();
        key = key * 131 + sPos[1];
        key = key * 131 + host.getHeight();
        return key;
    }


    private static void maintainDropletGeometry() {
        if (sLastIndex < 0 || sDropletRef.get() == null) {
            return;
        }
        ViewGroup tabRow = sTabRowRef.get();
        LiquidGlassHostLayout host = sHostRef.get();
        if (tabRow == null || host == null) {
            return;
        }
        View tab = TabBarBridge.tabAt(tabRow, sLastIndex);
        if (tab == null || !ViewGeom.positionIn(tab, host, sPos)) {
            return;
        }
        if (dropletGeometryKey(host, tab) == sDropletGeometryKey) {
            return;
        }
        syncDropletSize(sLastIndex);
    }
}
