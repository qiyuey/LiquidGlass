package top.qiyuey.liquidglass;

import java.util.Locale;

/** Exact names only: never hide a similarly named unrelated tab. */
final class WeWorkTabPolicy {
    private WeWorkTabPolicy() {}

    static boolean hideTitle(String title) {
        if (title == null) return false;
        switch (title.trim().toLowerCase(Locale.ROOT)) {
            case "channel":
            case "channels":
            case "doc":
            case "docs":
            case "documents":
            case "频道":
            case "頻道":
            case "文档":
            case "文檔":
                return true;
            default:
                return false;
        }
    }
}
