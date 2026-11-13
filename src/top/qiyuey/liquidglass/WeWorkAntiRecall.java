package top.qiyuey.liquidglass;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;

/** Retains recently displayed incoming text for the lifetime of the WeCom process. */
final class WeWorkAntiRecall {
    private static final String VIEWS = "com.tencent.wework.msg.views.";
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_TEXT_LENGTH = 8192;
    private static final int SUPPORTED_VERSION = 84802;

    private record MessageKey(long conversationId, long messageId) {}

    private final LinkedHashMap<MessageKey, String> recent = new LinkedHashMap<>();
    private final Method conversationId;
    private final Method messageId;
    private final Method content;
    private Boolean supportedVersion;

    private WeWorkAntiRecall(Class<?> item) throws ReflectiveOperationException {
        conversationId = item.getMethod("getConversationId");
        messageId = item.getMethod("getId");
        content = item.getMethod("getContent");
        conversationId.setAccessible(true);
        messageId.setAccessible(true);
        content.setAccessible(true);
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> item = loader.loadClass("oby");
            Class<?> conversation = loader.loadClass("s7d");
            Class<?> incoming = loader.loadClass(VIEWS + "MessageListIncomingTextItemView");
            Class<?> revoked = loader.loadClass(VIEWS + "MessageListRevokedMessageItemView");
            Method bindIncoming = incoming.getDeclaredMethod("g", conversation, item);
            Method showRevoked = revoked.getDeclaredMethod("setContent", CharSequence.class);
            Field revokedItem = revoked.getDeclaredField("o2");
            revokedItem.setAccessible(true);
            WeWorkAntiRecall feature = new WeWorkAntiRecall(item);

            LiquidGlassModule.hookAfter(bindIncoming, chain -> {
                View row = (View) chain.getThisObject();
                if (row.getClass() == incoming && feature.isSupported(row.getContext())) {
                    feature.remember(chain.getArg(1));
                }
            });
            LiquidGlassModule.hookIntercept(showRevoked, chain -> {
                View row = (View) chain.getThisObject();
                CharSequence notice = (CharSequence) chain.getArg(0);
                if (notice != null && feature.isSupported(row.getContext())) {
                    String original = feature.lookup(revokedItem.get(row));
                    if (original != null) {
                        CharSequence display = new SpannableStringBuilder(notice)
                                .append("\n").append(original);
                        return chain.proceed(new Object[]{display});
                    }
                }
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LiquidGlassModule.logErr("WeCom anti-recall views unavailable", e);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isSupported(Context context) {
        if (supportedVersion == null) {
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                supportedVersion = version == SUPPORTED_VERSION;
            } catch (PackageManager.NameNotFoundException e) {
                supportedVersion = false;
            }
        }
        return supportedVersion;
    }

    private MessageKey key(Object item) throws ReflectiveOperationException {
        if (item == null) return null;
        long conversation = (Long) conversationId.invoke(item);
        long message = (Long) messageId.invoke(item);
        return conversation == 0 || message == 0 ? null : new MessageKey(conversation, message);
    }

    private void remember(Object item) throws ReflectiveOperationException {
        MessageKey key = key(item);
        if (key == null) return;
        CharSequence text = (CharSequence) content.invoke(item);
        if (text == null || text.length() == 0 || text.length() > MAX_TEXT_LENGTH) return;
        recent.put(key, text.toString());
        if (recent.size() > MAX_MESSAGES) {
            recent.remove(recent.keySet().iterator().next());
        }
    }

    private String lookup(Object item) throws ReflectiveOperationException {
        return recent.get(key(item));
    }
}
