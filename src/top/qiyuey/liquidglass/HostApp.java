package top.qiyuey.liquidglass;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable host descriptors, registered once; new hosts supply their own strategy. */
final class HostApp {
    private static final Map<String, HostApp> REGISTRY;
    static {
        Map<String, HostApp> hosts = new HashMap<>();
        register(hosts, new HostApp("com.tencent.mm", "com.tencent.mm.ui.LauncherUI",
                new String[]{"com.tencent.mm.ui.LauncherUIBottomTabView"},
                new String[]{"setTo"}, "getCurIdx", "com.tencent.mm.ui.", new WeChatAdapter()));
        register(hosts, new HostApp("com.tencent.wework", "com.tencent.wework.launch.WwMainActivity",
                new String[]{"com.tencent.wework.common.views.SwitchTab"},
                new String[]{"setSelectedTab", "setSelectedTabForce", "setSelectedTabWithoutNotify"},
                "getSelectedTab", "com.tencent.wework.", new WeWorkAdapter()));
        REGISTRY = Collections.unmodifiableMap(hosts);
    }
    final String pkg;
    final String launcherActivity;
    final String[] tabViewClasses;
    final String[] tabSwitchMethods;
    final String currentIndexMethod;
    final String uiPrefix;
    final HostAdapter adapter;
    // Optional declarative chrome removal, shared by any future host.
    final String[] hiddenSiblings = new String[0];

    private HostApp(String pkg, String activity, String[] bars, String[] switchMethods,
                    String getter, String prefix, HostAdapter adapter) {
        this.pkg = pkg;
        this.launcherActivity = activity;
        this.tabViewClasses = bars;
        this.tabSwitchMethods = switchMethods;
        this.currentIndexMethod = getter;
        this.uiPrefix = prefix;
        this.adapter = adapter;
    }
    private static void register(Map<String, HostApp> registry, HostApp host) {
        if (registry.put(host.pkg, host) != null) throw new IllegalStateException("Duplicate host: " + host.pkg);
    }
    static HostApp forProcess(String process) { return REGISTRY.get(process); }
    static HostApp forPackage(String pkg) { return REGISTRY.get(pkg); }
    boolean isTabViewClass(String name) { return Arrays.asList(tabViewClasses).contains(name); }
    boolean isHiddenSibling(String name) { return Arrays.asList(hiddenSiblings).contains(name); }
    @Override public String toString() { return pkg; }
}
