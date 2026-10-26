package com.newline.tv.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.newline.tv.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Загрузка списка каналов: плейлист оператора или портал IPTVPORTAL. */
public final class PlaylistRepository {

    private static final int TIMEOUT_SECONDS = 15;
    private static final String TAG = "NewlineTV";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    /** OkHttp сам повторяет запрос на новом соединении и корректно ходит по редиректам. */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

    private static volatile Result pending;

    // Кэш последней удачной загрузки: переживает пересоздание Activity (смена темы, масштаба).
    private static volatile List<Channel> cachedChannels;
    private static volatile boolean cachedDemo;
    private static volatile String cachedKey;

    // Иконки из плейлиста оператора: нужны, когда портал не отдаёт logo_url.
    private static volatile Map<String, String> logoCatalog;
    private static volatile String logoCatalogUrl;

    private PlaylistRepository() {
    }

    public interface Callback {
        void onSuccess(List<Channel> channels, boolean demo);

        void onError(String message);
    }

    /** Результат загрузки, которым заставка делится с главным экраном. */
    public static final class Result {
        public final List<Channel> channels;
        public final boolean demo;
        public final String error;
        public final String key;

        private Result(List<Channel> channels, boolean demo, String error, String key) {
            this.channels = channels;
            this.demo = demo;
            this.error = error;
            this.key = key;
        }
    }

    public static void setPending(Result result) {
        pending = result;
    }

    public static Result takePending() {
        Result result = pending;
        pending = null;
        return result;
    }

    public static Result success(List<Channel> channels, boolean demo, String key) {
        return new Result(channels, demo, null, key);
    }

    public static Result failure(String error, String key) {
        return new Result(new ArrayList<Channel>(), false, error, key);
    }

    /** Последний успешный список, если он загружен для текущего источника. */
    public static List<Channel> getCached(String key) {
        String requested = key == null ? "" : key;
        String loaded = cachedKey == null ? "" : cachedKey;
        if (cachedChannels != null && loaded.equals(requested)) {
            return cachedChannels;
        }
        return null;
    }

    public static boolean isCachedDemo() {
        return cachedDemo;
    }

    public static void load(Context context, final Callback callback) {
        final String key = Settings.getSourceKey(context);
        final boolean portal = Settings.isPortalSource(context);
        final String url = Settings.getPlaylistUrl(context);
        final String domain = Settings.getPortalDomain(context);
        final String brand = context.getString(R.string.portal_domain);
        final String deviceId = Settings.getDeviceId(context);
        final String localIp = Settings.getLocalIpAddress();
        final Context appContext = context.getApplicationContext();

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Channel> channelList;
                    if (portal) {
                        Log.d(TAG, "loading channels from IPTVPORTAL: " + domain);
                        List<Channel> loaded;
                        try {
                            PortalClient.TerminalResult terminal =
                                    PortalClient.load(domain, brand, url, deviceId, localIp);
                            Settings.setPortalDomain(appContext, terminal.base);
                            loaded = terminal.channels;
                            if (loaded.isEmpty()) {
                                throw new IOException("Портал не вернул ни одного канала");
                            }
                        } catch (IOException portalError) {
                            // Портал недоступен (сеть, фильтрация портов) — не оставляем
                            // пользователя без телевидения: грузим плейлист оператора.
                            Log.w(TAG, "portal failed: " + portalError.getMessage()
                                    + ", fallback to operator playlist");
                            loaded = loadPlaylist(url);
                        }
                        if (needsLogos(loaded)) {
                            loaded = withLogos(loaded, url);
                        }
                        channelList = loaded;
                    } else {
                        channelList = loadPlaylist(url);
                    }

                    cachedChannels = channelList;
                    cachedDemo = false;
                    cachedKey = key;

                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(channelList, false);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "channels load failed: " + key, e);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            String message = e.getMessage();
                            if (message == null || message.trim().isEmpty()) {
                                message = portal
                                        ? "Проверьте домен и данные доступа к порталу"
                                        : "Проверьте соединение и ссылку на плейлист";
                            }
                            callback.onError(message);
                        }
                    });
                }
            }
        });
    }

    private static List<Channel> loadPlaylist(String url) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("Не задан плейлист оператора");
        }
        String body = httpGet(url.trim());
        List<Channel> channels = M3uParser.parse(body);
        Log.d(TAG, "parsed channels: " + channels.size());
        if (channels.isEmpty()) {
            throw new IOException("В плейлисте не найдено ни одного канала");
        }
        return channels;
    }

    private static boolean needsLogos(List<Channel> channels) {
        for (Channel channel : channels) {
            if (channel.logoUrl.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Портал не отдаёт logo_url и не у всех каналов путь вида play/<id>/index.m3u8,
     * поэтому недостающие иконки добираем из плейлиста оператора по названию канала.
     */
    private static List<Channel> withLogos(List<Channel> channels, String playlistUrl) {
        Map<String, String> logos = logoCatalog(playlistUrl);
        if (logos.isEmpty()) {
            return channels;
        }
        List<Channel> result = new ArrayList<>(channels.size());
        int filled = 0;
        for (Channel channel : channels) {
            String logo = channel.logoUrl.isEmpty() ? logos.get(nameKey(channel.name)) : null;
            if (logo == null) {
                result.add(channel);
            } else {
                result.add(new Channel(channel.id, channel.name, channel.url, logo,
                        channel.group, channel.epgId));
                filled++;
            }
        }
        if (filled > 0) {
            Log.d(TAG, "logos from playlist: +" + filled);
        }
        return result;
    }

    private static Map<String, String> logoCatalog(String playlistUrl) {
        if (playlistUrl == null || playlistUrl.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> cached = logoCatalog;
        if (cached != null && playlistUrl.equals(logoCatalogUrl)) {
            return cached;
        }
        try {
            Map<String, String> map = new LinkedHashMap<>();
            for (Channel channel : M3uParser.parse(httpGet(playlistUrl.trim()))) {
                if (!channel.logoUrl.isEmpty()) {
                    map.put(nameKey(channel.name), channel.logoUrl);
                }
            }
            if (!map.isEmpty()) {
                logoCatalog = map;
                logoCatalogUrl = playlistUrl;
            }
            return map;
        } catch (IOException e) {
            Log.w(TAG, "logo catalog failed: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Ключ сравнения названий: регистр, «ё», пробелы, скобки и знак «+» не важны. */
    private static String nameKey(String name) {
        if (name == null) {
            return "";
        }
        String value = name.toLowerCase(Locale.US).replace('ё', 'е');
        value = value.replaceAll("[\\s\\u00a0]+", "");
        // Портал: «Россия 1 + 2», плейлист: «Россия 1 (+2)» — скобки игнорируем.
        return value.replaceAll("[()\\[\\]{}]", "");
    }

    private static String httpGet(String urlString) throws IOException {
        try {
            return fetch(urlString);
        } catch (IOException httpError) {
            // Некоторые провайдерские серверы рвут HTTP-соединение, но отдают тот же файл по HTTPS.
            if (urlString.startsWith("http://")) {
                String httpsUrl = "https://" + urlString.substring("http://".length());
                Log.w(TAG, "http failed (" + httpError.getMessage() + "), retry over https: " + httpsUrl);
                try {
                    return fetch(httpsUrl);
                } catch (IOException httpsError) {
                    Log.e(TAG, "https retry failed: " + httpsError.getMessage());
                }
            }
            throw httpError;
        }
    }

    private static String fetch(String urlString) throws IOException {
        Request request = new Request.Builder()
                .url(urlString)
                .header("User-Agent", "NewlineTV/1.0")
                .header("Accept", "*/*")
                .build();

        Response response = CLIENT.newCall(request).execute();
        try {
            Log.d(TAG, "GET " + urlString + " -> " + response.code()
                    + ", type=" + response.header("Content-Type"));

            if (!response.isSuccessful()) {
                throw new IOException("Сервер ответил HTTP " + response.code());
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Сервер вернул пустой ответ");
            }

            String body = responseBody.string();
            if (body.startsWith("\uFEFF")) {
                body = body.substring(1);
            }

            Log.d(TAG, "body: " + body.length() + " chars, preview="
                    + body.substring(0, Math.min(160, body.length()))
                    .replace('\n', ' ').replace('\r', ' '));

            if (body.trim().isEmpty()) {
                throw new IOException("Сервер вернул пустой ответ");
            }
            return body;
        } finally {
            response.close();
        }
    }
}
