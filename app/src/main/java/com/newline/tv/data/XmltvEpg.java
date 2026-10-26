package com.newline.tv.data;

import android.content.Context;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Внешняя телепрограмма (XMLTV) для M3U-списка.
 *
 * В плейлисте NewLine TV у каналов нет tvg-id, поэтому каналы сопоставляются
 * по названию: «Первый канал HD» и «Первый канал» — один и тот же канал,
 * а «Россия 1 (+2)» — отдельная сдвинутая версия.
 *
 * Используется облегчённый файл IPTVX (10 дней, без описаний, ~19 МБ gzip).
 * Разобранная программа хранится в кэше приложения, поэтому повторный запуск
 * не требует скачивания.
 */
public final class XmltvEpg {

    private static final String TAG = "NewlineTV";
    private static final String EPG_URL = "https://iptvx.one/EPG_LITE";
    private static final String CACHE_NAME = "xmltv-epg.tsv";
    /** Версия формата кэша: при изменении правил сопоставления старый кэш сбрасывается. */
    private static final int CACHE_VERSION = 3;

    /** Как часто обновлять телепрограмму. */
    private static final long TTL_MS = 12L * 60L * 60L * 1000L;
    /** Если загрузка не удалась — попробовать снова через 5 минут. */
    private static final long FAIL_RETRY_MS = 5L * 60L * 1000L;
    /** Храним передачи за последние 12 часов и на 48 часов вперёд. */
    private static final long KEEP_PAST_MS = 12L * 60L * 60L * 1000L;
    private static final long KEEP_FUTURE_MS = 48L * 60L * 60L * 1000L;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build();

    private static final Object LOCK = new Object();
    private static final Map<String, List<EpgProgram>> PROGRAMS = new HashMap<>();
    private static String loadedSignature = "";
    private static long loadedAt;

    private static final ThreadLocal<SimpleDateFormat> XML_TIME =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    SimpleDateFormat format =
                            new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
                    format.setLenient(false);
                    return format;
                }
            };

    private XmltvEpg() {
    }

    /**
     * Возвращает телепрограмму канала. При первом обращении скачивает и
     * разбирает XMLTV (это долго — вызывать только из фонового потока).
     */
    public static List<EpgProgram> loadFor(Context context, List<Channel> channels,
                                           Channel channel) {
        if (channel == null) {
            return new ArrayList<EpgProgram>();
        }
        String key = normalizeName(channel.name);
        if (key.isEmpty()) {
            return new ArrayList<EpgProgram>();
        }

        String signature = signature(channels);
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            boolean fresh = signature.equals(loadedSignature)
                    && loadedAt > 0
                    && now - loadedAt < TTL_MS;
            if (!fresh) {
                boolean ok = readCache(context, signature, now);
                if (!ok) {
                    ok = parseAndCache(context, channels, signature, now);
                }
                if (!ok) {
                    if (!signature.equals(loadedSignature)) {
                        PROGRAMS.clear();
                        loadedSignature = signature;
                    }
                    loadedAt = now - TTL_MS + FAIL_RETRY_MS;
                }
            }
            List<EpgProgram> list = PROGRAMS.get(key);
            return list == null ? new ArrayList<EpgProgram>() : list;
        }
    }

    /** Нормализованное название канала — по нему сопоставляются M3U и XMLTV. */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String text = name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        text = text.replaceAll("\\(\\s*([+-])\\s*(\\d+)\\s*\\)", " $1$2 ");
        text = text.replaceAll("\\[\\s*([+-])\\s*(\\d+)\\s*\\]", " $1$2 ");
        text = text.replaceAll("\\([^)]*\\)", " ");
        text = text.replaceAll("\\[[^\\]]*\\]", " ");
        text = removeWord(text, "телеканал");
        text = removeWord(text, "тк");
        text = removeWord(text, "канал");
        text = removeWord(text, "hd");
        text = removeWord(text, "fhd");
        text = removeWord(text, "uhd");
        text = removeWord(text, "4k");
        text = removeWord(text, "sd");
        text = removeWord(text, "hevc");
        text = removeWord(text, "h\\.?265");
        // В названиях встречается латинское «TB» вместо кириллического «ТВ».
        text = text.replace("tv", "тв").replace("tb", "тв");
        text = text.replaceAll("[^0-9a-zа-я+\\-]+", " ");
        text = text.replaceAll("\\s*\\+\\s*", "+");
        text = text.replaceAll("\\s*-\\s*(?=\\d)", "-");
        text = text.replaceAll("\\s*\\+$", "");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    private static String removeWord(String text, String word) {
        return text.replaceAll(
                "(?<![0-9a-zа-я])" + word + "(?![0-9a-zа-я])", " ");
    }

    private static List<String> altKeys(String name) {
        String key = normalizeName(name);
        if (key.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> keys = new LinkedHashSet<String>();
        keys.add(key);
        String noShift = key.replaceAll("\\s*[+-]\\d+$", "").trim();
        if (!noShift.isEmpty()) {
            keys.add(noShift);
        }
        for (String base : new ArrayList<String>(keys)) {
            String noTv = base.replaceAll("\\s+тв$", "").trim();
            if (!noTv.isEmpty()) {
                keys.add(noTv);
            }
        }
        for (String base : new ArrayList<String>(keys)) {
            keys.add(base.replace(" ", ""));
        }
        return new ArrayList<String>(keys);
    }

    private static String signature(List<Channel> channels) {
        if (channels == null) {
            return "";
        }
        List<String> keys = new ArrayList<String>();
        for (Channel channel : channels) {
            String key = normalizeName(channel.name);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        Collections.sort(keys);
        StringBuilder joined = new StringBuilder();
        for (String key : keys) {
            joined.append(key).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(joined.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int value = b & 0xff;
                if (value < 16) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(value));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(joined.toString().hashCode())
                    + "-" + keys.size();
        }
    }

    private static boolean readCache(Context context, String signature, long now) {
        File file = new File(context.getCacheDir(), CACHE_NAME);
        if (!file.isFile()) {
            return false;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            String header = reader.readLine();
            if (header == null || !header.startsWith("# ")) {
                return false;
            }
            String[] headerParts = header.substring(2).split(" ");
            if (headerParts.length < 3
                    || !Integer.toString(CACHE_VERSION).equals(headerParts[0])
                    || !signature.equals(headerParts[1])) {
                return false;
            }
            long savedAt = Long.parseLong(headerParts[2]);
            if (now - savedAt > TTL_MS || savedAt > now + 60L * 60L * 1000L) {
                return false;
            }

            Map<String, List<EpgProgram>> parsed = new HashMap<String, List<EpgProgram>>();
            String line;
            while ((line = reader.readLine()) != null) {
                int first = line.indexOf('\t');
                int second = first < 0 ? -1 : line.indexOf('\t', first + 1);
                int third = second < 0 ? -1 : line.indexOf('\t', second + 1);
                if (third < 0) {
                    continue;
                }
                String key = line.substring(0, first);
                long start = Long.parseLong(line.substring(first + 1, second));
                long stop = Long.parseLong(line.substring(second + 1, third));
                String title = line.substring(third + 1);
                List<EpgProgram> list = parsed.get(key);
                if (list == null) {
                    list = new ArrayList<EpgProgram>();
                    parsed.put(key, list);
                }
                list.add(new EpgProgram(start, stop, title, "", 0));
            }

            PROGRAMS.clear();
            PROGRAMS.putAll(parsed);
            loadedSignature = signature;
            loadedAt = savedAt;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "XMLTV EPG: не удалось прочитать кэш", e);
            return false;
        } finally {
            closeQuietly(reader);
        }
    }

    private static boolean parseAndCache(Context context, List<Channel> channels,
                                         String signature, long now) {
        if (channels == null || channels.isEmpty()) {
            return false;
        }

        // Ключи сопоставления: точное название приоритетнее «примерного».
        Map<String, String> canonicalByKey = new HashMap<String, String>();
        Set<String> seen = new HashSet<String>();
        for (Channel channel : channels) {
            String canonical = normalizeName(channel.name);
            if (!canonical.isEmpty() && seen.add(canonical)) {
                canonicalByKey.put(canonical, canonical);
            }
        }
        for (Channel channel : channels) {
            String canonical = normalizeName(channel.name);
            if (canonical.isEmpty()) {
                continue;
            }
            for (String key : altKeys(channel.name)) {
                if (!canonicalByKey.containsKey(key)) {
                    canonicalByKey.put(key, canonical);
                }
            }
        }
        if (canonicalByKey.isEmpty()) {
            return false;
        }

        Map<String, String> idToCanonical = new HashMap<String, String>();
        Map<String, List<EpgProgram>> parsed = new HashMap<String, List<EpgProgram>>();

        InputStream input = null;
        Response response = null;
        try {
            Request request = new Request.Builder()
                    .url(EPG_URL)
                    .header("Accept-Encoding", "identity")
                    .build();
            response = CLIENT.newCall(request).execute();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("пустой ответ");
            }
            input = maybeGunzip(body.byteStream());

            XmlPullParser parser = newParser();
            parser.setInput(input, "UTF-8");

            int event = parser.getEventType();
            boolean inChannel = false;
            boolean inDisplayName = false;
            boolean inProgramme = false;
            boolean inTitle = false;
            String channelId = null;
            String programmeCanonical = null;
            long programmeStart = 0;
            long programmeStop = 0;
            String programmeTitle = null;
            StringBuilder buffer = new StringBuilder();
            List<String> displayNames = new ArrayList<String>();

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("channel".equals(tag)) {
                        inChannel = true;
                        channelId = parser.getAttributeValue(null, "id");
                        displayNames.clear();
                    } else if (inChannel && "display-name".equals(tag)) {
                        inDisplayName = true;
                        buffer.setLength(0);
                    } else if ("programme".equals(tag)) {
                        String id = parser.getAttributeValue(null, "channel");
                        programmeCanonical = id == null ? null : idToCanonical.get(id);
                        programmeStart = parseTime(parser.getAttributeValue(null, "start"));
                        programmeStop = parseTime(parser.getAttributeValue(null, "stop"));
                        programmeTitle = null;
                        inProgramme = programmeCanonical != null
                                && programmeStop > programmeStart;
                    } else if (inProgramme && "title".equals(tag)) {
                        inTitle = true;
                        buffer.setLength(0);
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (inDisplayName || inTitle) {
                        buffer.append(parser.getText());
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("display-name".equals(tag) && inChannel) {
                        inDisplayName = false;
                        displayNames.add(buffer.toString().trim());
                    } else if ("channel".equals(tag)) {
                        inChannel = false;
                        inDisplayName = false;
                        if (channelId != null) {
                            for (String displayName : displayNames) {
                                String canonical = canonicalFor(displayName, canonicalByKey);
                                if (canonical != null) {
                                    idToCanonical.put(channelId, canonical);
                                    break;
                                }
                            }
                        }
                    } else if ("title".equals(tag) && inProgramme) {
                        inTitle = false;
                        if (programmeTitle == null) {
                            programmeTitle = buffer.toString().trim();
                        }
                    } else if ("programme".equals(tag)) {
                        if (inProgramme && programmeTitle != null
                                && !programmeTitle.isEmpty()
                                && programmeStop > now - KEEP_PAST_MS
                                && programmeStart < now + KEEP_FUTURE_MS) {
                            List<EpgProgram> list = parsed.get(programmeCanonical);
                            if (list == null) {
                                list = new ArrayList<EpgProgram>();
                                parsed.put(programmeCanonical, list);
                            }
                            list.add(new EpgProgram(
                                    programmeStart, programmeStop, programmeTitle, "", 0));
                        }
                        inProgramme = false;
                        inTitle = false;
                        programmeCanonical = null;
                    }
                }
                event = parser.next();
            }
        } catch (Exception e) {
            Log.w(TAG, "XMLTV EPG: не удалось загрузить", e);
            return false;
        } finally {
            closeQuietly(input);
            if (response != null) {
                response.close();
            }
        }

        removeDuplicates(parsed);
        writeCache(context, signature, now, parsed);

        PROGRAMS.clear();
        PROGRAMS.putAll(parsed);
        loadedSignature = signature;
        loadedAt = now;
        return true;
    }

    private static XmlPullParser newParser() throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        XmlPullParser parser = factory.newPullParser();
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        } catch (Exception ignored) {
            // Не все реализации поддерживают эту настройку.
        }
        return parser;
    }

    private static String canonicalFor(String displayName,
                                       Map<String, String> canonicalByKey) {
        String exact = normalizeName(displayName);
        String canonical = canonicalByKey.get(exact);
        if (canonical != null) {
            return canonical;
        }
        // Неподписанный канал не должен подхватывать программу со сдвигом +N:
        // у «Карусели» и «Карусели +2» разные сетки вещания.
        boolean shifted = exact.matches(".*[+-][1-9]\\d*$");
        String exactNoSpaces = exact.replace(" ", "");
        for (String key : altKeys(displayName)) {
            if (shifted && !key.equals(exact) && !key.equals(exactNoSpaces)) {
                continue;
            }
            canonical = canonicalByKey.get(key);
            if (canonical != null) {
                return canonical;
            }
        }
        return null;
    }

    private static long parseTime(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return XML_TIME.get().parse(value.trim()).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    private static void removeDuplicates(Map<String, List<EpgProgram>> parsed) {
        for (Map.Entry<String, List<EpgProgram>> entry : parsed.entrySet()) {
            List<EpgProgram> list = entry.getValue();
            Collections.sort(list, new Comparator<EpgProgram>() {
                @Override
                public int compare(EpgProgram a, EpgProgram b) {
                    if (a.start != b.start) {
                        return a.start < b.start ? -1 : 1;
                    }
                    if (a.stop != b.stop) {
                        return a.stop < b.stop ? -1 : 1;
                    }
                    return a.title.compareTo(b.title);
                }
            });
            List<EpgProgram> unique = new ArrayList<EpgProgram>(list.size());
            EpgProgram previous = null;
            for (EpgProgram program : list) {
                if (previous != null && previous.start == program.start
                        && previous.title.equals(program.title)) {
                    continue;
                }
                unique.add(program);
                previous = program;
            }
            entry.setValue(unique);
        }
    }

    private static void writeCache(Context context, String signature, long now,
                                   Map<String, List<EpgProgram>> parsed) {
        File file = new File(context.getCacheDir(), CACHE_NAME);
        File temp = new File(context.getCacheDir(), CACHE_NAME + ".tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(temp), "UTF-8"));
            writer.write("# ");
            writer.write(Integer.toString(CACHE_VERSION));
            writer.write(' ');
            writer.write(signature);
            writer.write(' ');
            writer.write(Long.toString(now));
            writer.write('\n');
            for (Map.Entry<String, List<EpgProgram>> entry : parsed.entrySet()) {
                String key = entry.getKey();
                for (EpgProgram program : entry.getValue()) {
                    writer.write(key);
                    writer.write('\t');
                    writer.write(Long.toString(program.start));
                    writer.write('\t');
                    writer.write(Long.toString(program.stop));
                    writer.write('\t');
                    writer.write(cleanLine(program.title));
                    writer.write('\n');
                }
            }
            writer.close();
            writer = null;
            if (!temp.renameTo(file)) {
                // На Android переименование поверх файла обычно работает;
                // если нет — пробуем ещё раз, удалив старый кэш.
                if (!file.delete() || !temp.renameTo(file)) {
                    Log.w(TAG, "XMLTV EPG: не удалось сохранить кэш");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "XMLTV EPG: не удалось записать кэш", e);
        } finally {
            closeQuietly(writer);
            if (temp.exists() && !file.exists()) {
                temp.delete();
            }
        }
    }

    private static String cleanLine(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static InputStream maybeGunzip(InputStream raw) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(raw, 2);
        byte[] magic = new byte[2];
        int read = pushback.read(magic, 0, 2);
        if (read > 0) {
            pushback.unread(magic, 0, read);
        }
        if (read == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(pushback);
        }
        return pushback;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
