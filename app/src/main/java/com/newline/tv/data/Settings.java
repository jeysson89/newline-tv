package com.newline.tv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.TextUtils;

import com.newline.tv.R;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Настройки приложения и идентификатор устройства. */
public final class Settings {

    private static final String PREFS = "newline_tv_prefs";

    private static final String KEY_SOURCE = "source_type";
    private static final String KEY_PORTAL_DOMAIN = "portal_domain";
    private static final String KEY_FONT_SCALE = "font_scale";
    private static final String KEY_EPG_FONT_SCALE = "epg_font_scale";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_LAST_CHANNEL_URL = "last_channel_url";
    private static final String KEY_LAST_CHANNEL_NAME = "last_channel_name";
    private static final String KEY_PANEL_ENABLED = "panel_enabled";
    private static final String KEY_PANEL_TRANSPARENCY = "panel_transparency";
    private static final String KEY_CHANNEL_VIEW = "channel_view";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final String KEY_LAST_UPDATE_ATTEMPT = "last_update_attempt";

    /** Плейлист оператора по умолчанию (не менять). */
    public static final String DEFAULT_PLAYLIST_URL = "http://tv.isp-nl.ru/nltv.m3u";

    public static final String SOURCE_M3U = "m3u";
    public static final String SOURCE_PORTAL = "portal";

    /** Вид списка каналов: плитки (как раньше) или компактные строки. */
    public static final String VIEW_CARDS = "cards";
    public static final String VIEW_LIST = "list";

    /** Тема: следовать системе / всегда светлая / всегда тёмная. */
    public static final int THEME_SYSTEM = -1;
    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK = 1;

    public static final float FONT_SCALE_MIN = 0.50f;
    public static final float FONT_SCALE_MAX = 1.50f;

    /** Прозрачность фона списка каналов: 0 — непрозрачный, 100 — полностью прозрачный. */
    public static final int PANEL_TRANSPARENCY_MIN = 0;
    public static final int PANEL_TRANSPARENCY_MAX = 100;
    public static final int PANEL_TRANSPARENCY_DEFAULT = 30;

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Плейлист оператора зашит в приложение и не изменяется. */
    public static String getPlaylistUrl(Context context) {
        return DEFAULT_PLAYLIST_URL;
    }

    /** Последний просмотренный канал — чтобы при входе сразу открыть его. */
    public static void setLastChannel(Context context, String url, String name) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        prefs(context).edit()
                .putString(KEY_LAST_CHANNEL_URL, url.trim())
                .putString(KEY_LAST_CHANNEL_NAME, name == null ? "" : name.trim())
                .apply();
    }

    public static String getLastChannelUrl(Context context) {
        return prefs(context).getString(KEY_LAST_CHANNEL_URL, "");
    }

    public static String getLastChannelName(Context context) {
        return prefs(context).getString(KEY_LAST_CHANNEL_NAME, "");
    }

    /** Показывать ли список каналов поверх видео по кнопке «Назад». */
    public static boolean isPanelEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PANEL_ENABLED, true);
    }

    public static void setPanelEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PANEL_ENABLED, enabled).apply();
    }

    /** Прозрачность фона списка каналов в процентах (0 — плотный фон, 100 — без фона). */
    public static int getPanelTransparency(Context context) {
        int value = prefs(context).getInt(KEY_PANEL_TRANSPARENCY, PANEL_TRANSPARENCY_DEFAULT);
        return Math.max(PANEL_TRANSPARENCY_MIN, Math.min(PANEL_TRANSPARENCY_MAX, value));
    }

    public static void setPanelTransparency(Context context, int percent) {
        int value = Math.max(PANEL_TRANSPARENCY_MIN, Math.min(PANEL_TRANSPARENCY_MAX, percent));
        prefs(context).edit().putInt(KEY_PANEL_TRANSPARENCY, value).apply();
    }

    /** Выбранный вид списка каналов: «Плитки» или «Список». */
    public static String getChannelView(Context context) {
        String value = prefs(context).getString(KEY_CHANNEL_VIEW, VIEW_CARDS);
        return VIEW_LIST.equals(value) ? VIEW_LIST : VIEW_CARDS;
    }

    public static void setChannelView(Context context, String view) {
        prefs(context).edit()
                .putString(KEY_CHANNEL_VIEW, VIEW_LIST.equals(view) ? VIEW_LIST : VIEW_CARDS)
                .apply();
    }

    /** Когда последний раз автоматически проверяли обновления (мс с эпохи). */
    public static long getLastUpdateCheck(Context context) {
        return prefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L);
    }

    public static void setLastUpdateCheck(Context context, long timestamp) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK, timestamp).apply();
    }

    /** Последняя попытка проверки — чтобы не долбить сеть при ошибках. */
    public static long getLastUpdateAttempt(Context context) {
        return prefs(context).getLong(KEY_LAST_UPDATE_ATTEMPT, 0L);
    }

    public static void setLastUpdateAttempt(Context context, long timestamp) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_ATTEMPT, timestamp).apply();
    }

    public static String getSource(Context context) {
        return prefs(context).getString(KEY_SOURCE, SOURCE_M3U);
    }

    public static void setSource(Context context, String source) {
        prefs(context).edit()
                .putString(KEY_SOURCE, SOURCE_PORTAL.equals(source) ? SOURCE_PORTAL : SOURCE_M3U)
                .apply();
    }

    public static boolean isPortalSource(Context context) {
        return SOURCE_PORTAL.equals(getSource(context));
    }

    /** Ключ, по которому кэшируется и сравнивается текущий список каналов. */
    public static String getSourceKey(Context context) {
        if (isPortalSource(context)) {
            return "portal://" + getDeviceId(context);
        }
        String url = getPlaylistUrl(context);
        return "m3u://" + (url == null ? "" : url.trim());
    }

    /** Домен оператора по умолчанию задаётся брендом приложения (values/strings.xml). */
    public static String getPortalDomain(Context context) {
        return prefs(context).getString(KEY_PORTAL_DOMAIN,
                context.getString(R.string.portal_domain));
    }

    public static void setPortalDomain(Context context, String domain) {
        prefs(context).edit().putString(KEY_PORTAL_DOMAIN, domain == null ? "" : domain.trim()).apply();
    }

    public static float getFontScale(Context context) {
        float value = prefs(context).getFloat(KEY_FONT_SCALE, 1.0f);
        return Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, value));
    }

    public static void setFontScale(Context context, float scale) {
        float value = Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, scale));
        prefs(context).edit().putFloat(KEY_FONT_SCALE, value).apply();
    }

    /** Размер шрифта в диалоге телепрограммы — отдельно от общего масштаба интерфейса. */
    public static float getEpgFontScale(Context context) {
        // Пока пользователь не трогал отдельный ползунок, сохраняем прежний вид:
        // телепрограмма масштабируется вместе с интерфейсом.
        float fallback = getFontScale(context);
        float value = prefs(context).getFloat(KEY_EPG_FONT_SCALE, fallback);
        return Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, value));
    }

    public static void setEpgFontScale(Context context, float scale) {
        float value = Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, scale));
        prefs(context).edit().putFloat(KEY_EPG_FONT_SCALE, value).apply();
    }

    public static int getThemeMode(Context context) {
        return prefs(context).getInt(KEY_THEME, THEME_SYSTEM);
    }

    public static void setThemeMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply();
    }

    /** Тёмная тема включена сейчас (с учётом системной настройки). */
    public static boolean isDarkEffective(Context context) {
        int mode = getThemeMode(context);
        if (mode == THEME_DARK) {
            return true;
        }
        if (mode == THEME_LIGHT) {
            return false;
        }
        int uiMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Локальный IPv4-адрес устройства — портал использует его вместе с MAC. */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || !nif.isUp()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** MAC-адрес, если доступен, иначе стабильный идентификатор Android. */
    public static String getDeviceId(Context context) {
        String mac = readMacAddress();
        if (!TextUtils.isEmpty(mac)) {
            return mac;
        }
        String androidId = android.provider.Settings.Secure.getString(
                context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (TextUtils.isEmpty(androidId)) {
            androidId = "0000000000000000";
        }
        return "NEWLINE-" + androidId.toUpperCase(Locale.US);
    }

    private static String readMacAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || nif.isVirtual() || !nif.isUp()) {
                    continue;
                }
                byte[] address = nif.getHardwareAddress();
                if (address == null || address.length != 6) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                boolean zeroes = true;
                for (int i = 0; i < address.length; i++) {
                    if (address[i] != 0) {
                        zeroes = false;
                    }
                    if (i > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format(Locale.US, "%02X", address[i]));
                }
                String mac = sb.toString();
                if (!zeroes && !"02:00:00:00:00:00".equals(mac)) {
                    return mac;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
