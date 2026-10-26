package com.newline.tv.data;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Прямая работа с API IPTVPORTAL: автоопределение домена оператора и получение
 * списка каналов терминала через authorize_terminal.
 *
 * Документация: https://iptvportal.ru/doc/api/
 */
public final class PortalClient {

    private static final String TAG = "NewlineTV";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build();

    private static final OkHttpClient PROBE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .build();

    private PortalClient() {
    }

    public static final class TerminalResult {
        public final String base;
        public final boolean anonymous;
        public final List<Channel> channels;

        TerminalResult(String base, boolean anonymous, List<Channel> channels) {
            this.base = base;
            this.anonymous = anonymous;
            this.channels = channels;
        }
    }

    /** Загружает каналы с портала, автоматически подбирая рабочий домен оператора. */
    public static TerminalResult load(String savedDomain, String brand, String playlistUrl,
                                      String deviceMac, String deviceIp) throws IOException {
        IOException last = null;
        for (String base : candidateBases(savedDomain, brand, playlistUrl)) {
            try {
                TerminalResult result = loadFrom(base, deviceMac, deviceIp);
                if (!result.channels.isEmpty()) {
                    Log.d(TAG, "portal " + base + ": " + result.channels.size() + " channels");
                    return result;
                }
                last = new IOException("Портал не вернул ни одного телеканала");
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "portal " + base + " failed: " + e.getMessage());
            }
        }
        throw last == null ? new IOException("Не удалось определить домен портала") : last;
    }

    /** Определяет и возвращает рабочий адрес портала (для показа в настройках). */
    public static String resolveBase(String savedDomain, String brand, String playlistUrl)
            throws IOException {
        IOException last = null;
        for (String base : candidateBases(savedDomain, brand, playlistUrl)) {
            try {
                if (probe(base)) {
                    return base;
                }
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "portal probe " + base + " failed: " + e.getMessage());
            }
        }
        throw last == null ? new IOException("Не удалось определить домен портала") : last;
    }

    /** Телепрограмма канала: портал отдаёт список передач по channel_id. */
    public static List<EpgProgram> loadEpg(String base, int channelId) throws IOException {
        List<EpgProgram> programs = new ArrayList<>();
        if (TextUtils.isEmpty(base) || channelId <= 0) {
            return programs;
        }

        JSONObject params = new JSONObject();
        try {
            params.put("channel_id", channelId);
        } catch (JSONException e) {
            throw new IOException("Не удалось подготовить запрос телепрограммы", e);
        }

        JSONObject response = rpc(endpoint(base), "get_epg", params);
        JSONObject result = response.optJSONObject("result");
        if (result == null) {
            return programs;
        }
        return parseEpg(result.optJSONArray("programs"));
    }

    private static List<EpgProgram> parseEpg(JSONArray programs) {
        List<EpgProgram> result = new ArrayList<>();
        if (programs == null) {
            return result;
        }
        for (int i = 0; i < programs.length(); i++) {
            JSONObject item = programs.optJSONObject(i);
            if (item == null) {
                continue;
            }
            long start = parseDate(value(item, "start"));
            long stop = parseDate(value(item, "stop"));
            String title = value(item, "title");
            if (TextUtils.isEmpty(title) || stop <= start) {
                continue;
            }
            result.add(new EpgProgram(start, stop, title, value(item, "description"),
                    item.optInt("age_limit", 0)));
        }
        return result;
    }

    /** Дата портала: «2026-10-23 21:45:00+0300». */
    private static long parseDate(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0L;
        }
        String[] patterns = {"yyyy-MM-dd HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd HH:mm:ss"};
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                java.util.Date date = format.parse(value.trim());
                if (date != null) {
                    return date.getTime();
                }
            } catch (java.text.ParseException ignored) {
            }
        }
        return 0L;
    }

    private static String endpoint(String base) {
        String value = base == null ? "" : base.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return "";
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        return value + "/api/jsonrpc/";
    }

    public static String displayDomain(String base) {
        if (base == null) {
            return "";
        }
        String value = base.trim();
        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        } else if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * Домен портала оператора закреплён: <brand>.iptvportal.ru (для NEWLINE это
     * newline.iptvportal.ru). Угадывать адрес по имени файла плейлиста нельзя:
     * nltv.m3u давал нерабочий nltv.iptvportal.ru (HTTP 404), из-за чего портал
     * выглядел «не работающим». Сохранённый домен используем только как резерв.
     */
    static List<String> candidateBases(String savedDomain, String brand, String playlistUrl) {
        Set<String> bases = new LinkedHashSet<>();
        addVariants(bases, brand);
        if (bases.isEmpty()) {
            addVariants(bases, savedDomain);
        }
        return new ArrayList<>(bases);
    }

    private static void addVariants(Set<String> out, String value) {
        if (value == null) {
            return;
        }
        String cleaned = value.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            return;
        }
        if (cleaned.contains("://")) {
            out.add(cleaned);
            return;
        }
        if (cleaned.contains(".iptvportal.")) {
            out.add("https://" + cleaned);
            return;
        }
        out.add("https://" + cleaned + ".iptvportal.ru");
    }

    private static boolean probe(String base) throws IOException {
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"get_version\",\"params\":{}}";
        String text = post(base + "/api/jsonrpc/", payload, null, PROBE_CLIENT);
        return text.contains("\"jsonrpc\"");
    }

    private static TerminalResult loadFrom(String base, String mac, String ip) throws IOException {
        JSONObject params = new JSONObject();
        try {
            params.put("mac", mac == null ? "" : mac);
            if (!TextUtils.isEmpty(ip)) {
                params.put("ip", ip);
            }
        } catch (JSONException e) {
            throw new IOException("Не удалось подготовить запрос к порталу", e);
        }

        JSONObject response = rpc(base + "/api/jsonrpc/", "authorize_terminal", params);
        JSONObject result = response.optJSONObject("result");
        if (result == null) {
            throw new IOException(errorText(response, "Портал не вернул профиль терминала"));
        }

        JSONArray media = result.optJSONArray("medialist");
        if (media == null) {
            media = result.optJSONArray("media");
        }
        if (media == null) {
            throw new IOException("В ответе портала нет списка каналов");
        }

        List<Channel> channels = parseMedia(media, result.optJSONArray("playlists"));
        return new TerminalResult(base, result.optBoolean("anonymous", false), channels);
    }

    private static List<Channel> parseMedia(JSONArray media, JSONArray playlists) {
        Map<String, Channel> byId = new LinkedHashMap<>();
        for (int i = 0; i < media.length(); i++) {
            JSONObject item = media.optJSONObject(i);
            if (item == null || !item.optBoolean("is_tv", false)) {
                continue;
            }

            String id = value(item, "id");
            String name = value(item, "name");
            if (TextUtils.isEmpty(name)) {
                name = value(item, "title");
            }
            String url = buildUrl(value(item, "protocol"), value(item, "host"),
                    value(item, "port"), value(item, "path"));
            if (TextUtils.isEmpty(url)) {
                continue;
            }
            String logo = value(item, "logo_url");
            if (TextUtils.isEmpty(logo)) {
                // Портал не отдаёт logo_url — берём иконку из каталога вещания
                // (в M3U она лежит в .../playlist/tvg-logo/<id>.png).
                logo = buildLogoUrl(value(item, "protocol"), value(item, "host"),
                        value(item, "port"), value(item, "path"));
            }
            byId.put(id, new Channel(id, name, url, logo, "IPTV", item.optInt("channel_id", 0)));
        }

        List<Channel> ordered = new ArrayList<>();
        for (String id : tvPlaylistIds(playlists)) {
            Channel channel = byId.remove(id);
            if (channel != null) {
                ordered.add(channel);
            }
        }
        ordered.addAll(byId.values());
        return ordered;
    }

    /** Порядок каналов как в рубрикаторе оператора (плейлист «tv»). */
    private static List<String> tvPlaylistIds(JSONArray playlists) {
        List<String> ids = new ArrayList<>();
        if (playlists == null) {
            return ids;
        }
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist == null || !"tv".equals(playlist.optString("name"))) {
                continue;
            }
            JSONArray items = playlist.optJSONArray("playitems");
            if (items == null) {
                continue;
            }
            for (int j = 0; j < items.length(); j++) {
                ids.add(String.valueOf(items.opt(j)));
            }
            break;
        }
        return ids;
    }

    private static String buildUrl(String protocol, String host, String portValue, String path) {
        String link = path == null ? "" : path.trim();
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        if (TextUtils.isEmpty(host)) {
            return "";
        }

        String scheme = TextUtils.isEmpty(protocol) ? "http" : protocol.trim().toLowerCase(Locale.US);
        if (scheme.equals("hls")) {
            scheme = "http";
        }

        int port = 0;
        try {
            port = Integer.parseInt(portValue == null ? "" : portValue.trim());
        } catch (NumberFormatException ignored) {
        }

        StringBuilder url = new StringBuilder(scheme).append("://").append(host.trim());
        boolean defaultPort = (port <= 0)
                || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        if (!defaultPort) {
            url.append(':').append(port);
        }
        if (!link.isEmpty() && !link.startsWith("/")) {
            url.append('/');
        }
        url.append(link);
        return url.toString();
    }

    /** Иконка канала из каталога вещания: .../play/<id>/index.m3u8 -> .../playlist/tvg-logo/<id>.png */
    private static String buildLogoUrl(String protocol, String host, String portValue, String path) {
        String id = channelFileId(path);
        if (TextUtils.isEmpty(id)) {
            return "";
        }
        return buildUrl(protocol, host, portValue, "playlist/tvg-logo/" + id + ".png");
    }

    private static String channelFileId(String path) {
        String link = path == null ? "" : path.trim();
        int query = link.indexOf('?');
        if (query >= 0) {
            link = link.substring(0, query);
        }
        String[] parts = link.split("/");
        if (parts.length < 3) {
            return "";
        }
        String last = parts[parts.length - 1];
        if (last.isEmpty() || !last.startsWith("index.")) {
            return "";
        }
        if (!"play".equalsIgnoreCase(parts[parts.length - 3])) {
            return "";
        }
        return parts[parts.length - 2];
    }

    private static String value(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return "";
        }
        return object.optString(key, "");
    }

    private static JSONObject rpc(String url, String method, JSONObject params) throws IOException {
        try {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", "2.0");
            payload.put("id", 1);
            payload.put("method", method);
            payload.put("params", params);

            String text = post(url, payload.toString(), null, CLIENT);
            JSONObject response = new JSONObject(text);
            if (response.has("error")) {
                throw new IOException(errorText(response, "Ошибка портала"));
            }
            return response;
        } catch (JSONException e) {
            throw new IOException("Некорректный ответ портала", e);
        }
    }

    private static String post(String url, String body, String session, OkHttpClient client)
            throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "NewlineTV/1.0")
                .post(RequestBody.create(body, JSON));
        if (!TextUtils.isEmpty(session)) {
            builder.header("Iptvportal-Authorization", "sessionid=" + session);
        }

        Response response = client.newCall(builder.build()).execute();
        try {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            Log.d(TAG, "POST " + url + " -> " + response.code() + ", " + text.length() + " chars");
            if (!response.isSuccessful()) {
                throw new IOException("Портал ответил HTTP " + response.code());
            }
            if (TextUtils.isEmpty(text.trim())) {
                throw new IOException("Портал вернул пустой ответ");
            }
            return text;
        } finally {
            response.close();
        }
    }

    private static String errorText(JSONObject response, String fallback) {
        JSONObject error = response.optJSONObject("error");
        if (error == null) {
            return fallback;
        }
        String message = error.optString("message", "");
        int code = error.optInt("code", 0);
        if (TextUtils.isEmpty(message)) {
            return fallback;
        }
        return fallback + ": " + message + (code == 0 ? "" : " (" + code + ")");
    }
}
