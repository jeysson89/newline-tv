package com.newline.tv.data;

import android.content.Context;
import android.util.Log;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
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
 * Файл распакованного XML — около 240 МБ и 1,5 млн передач, поэтому обычный
 * XML-парсер на слабых приставках захлёбывается. Здесь XML читается потоковым
 * сканером: разбираются только передачи нужных каналов, остальные блоки просто
 * пропускаются. Кроме того, файл отсортирован по каналам, поэтому после сбора
 * последнего нужного канала чтение прекращается.
 *
 * Разобранная программа хранится в кэше приложения, поэтому повторный запуск
 * не требует скачивания.
 */
public final class XmltvEpg {

    private static final String TAG = "NewlineTV";
    private static final String EPG_URL = "https://iptvx.one/EPG_LITE";
    private static final String CACHE_NAME = "xmltv-epg.tsv";
    /** Версия формата кэша: при изменении правил сопоставления старый кэш сбрасывается. */
    private static final int CACHE_VERSION = 4;

    /** Как часто обновлять телепрограмму. */
    private static final long TTL_MS = 12L * 60L * 60L * 1000L;
    /** Если загрузка не удалась — попробовать снова через 5 минут. */
    private static final long FAIL_RETRY_MS = 5L * 60L * 1000L;
    /** Неполная загрузка (оборвалась связь): повторить через 10 минут. */
    private static final long PARTIAL_RETRY_MS = 10L * 60L * 1000L;
    /** Храним передачи за последние 12 часов и на 48 часов вперёд. */
    private static final long KEEP_PAST_MS = 12L * 60L * 60L * 1000L;
    private static final long KEEP_FUTURE_MS = 48L * 60L * 60L * 1000L;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build();

    /**
     * Телепрограмма по нормализованному названию канала. Чтение идёт без блокировок:
     * список подменяется целиком, когда разбор канала завершён, поэтому интерфейс
     * может показывать уже готовые данные, пока остальные ещё загружаются.
     */
    private static final ConcurrentHashMap<String, List<EpgProgram>> PROGRAMS =
            new ConcurrentHashMap<String, List<EpgProgram>>();

    /** Блокировка нужна только для того, чтобы один поток качал и разбирал файл. */
    private static final Object LOAD_LOCK = new Object();

    /** Каналы, для которых уже запущено фоновое «прогревание» телепрограммы. */
    private static final Set<String> WARMING = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private static String loadedSignature = "";
    private static long loadedAt;
    private static boolean partial;

    private XmltvEpg() {
    }

    /**
     * Возвращает телепрограмму канала. При первом обращении скачивает и
     * разбирает XMLTV (это долго — вызывать только из фонового потока).
     */
    public static List<EpgProgram> loadFor(Context context, List<Channel> channels,
                                           Channel channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        String key = normalizeName(channel.name);
        if (key.isEmpty()) {
            return Collections.emptyList();
        }

        String signature = signature(channels);
        synchronized (LOAD_LOCK) {
            long now = System.currentTimeMillis();
            long ttl = partial ? PARTIAL_RETRY_MS : TTL_MS;
            boolean fresh = signature.equals(loadedSignature)
                    && loadedAt > 0
                    && now - loadedAt < ttl;
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
                    partial = false;
                    loadedAt = now - TTL_MS + FAIL_RETRY_MS;
                }
            }
            List<EpgProgram> list = PROGRAMS.get(key);
            return list == null ? Collections.<EpgProgram>emptyList() : list;
        }
    }

    /**
     * Уже загруженная телепрограмма без обращения к сети — для обновления
     * открытого диалога, пока файл ещё разбирается в фоне.
     */
    public static List<EpgProgram> peek(Channel channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        List<EpgProgram> list = PROGRAMS.get(normalizeName(channel.name));
        return list == null ? Collections.<EpgProgram>emptyList() : list;
    }

    /** Есть ли хоть какие-то данные (кэш или частичный разбор). */
    public static boolean hasData() {
        return !PROGRAMS.isEmpty();
    }

    /**
     * Загружает телепрограмму заранее, не дожидаясь открытия диалога:
     * к моменту нажатия «ОК» данные обычно уже готовы.
     */
    public static void warmUp(Context context, List<Channel> channels) {
        if (context == null || channels == null || channels.isEmpty()) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String signature = signature(channels);
        synchronized (LOAD_LOCK) {
            long ttl = partial ? PARTIAL_RETRY_MS : TTL_MS;
            if (signature.equals(loadedSignature) && loadedAt > 0
                    && System.currentTimeMillis() - loadedAt < ttl) {
                return;
            }
        }
        if (!WARMING.add(signature)) {
            return;
        }
        final List<Channel> list = channels;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loadFor(app, list, list.get(0));
                } finally {
                    WARMING.remove(signature);
                }
            }
        }, "epg-warmup");
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
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
            boolean savedPartial = headerParts.length > 3 && "1".equals(headerParts[3]);
            long ttl = savedPartial ? PARTIAL_RETRY_MS : TTL_MS;
            if (now - savedAt > ttl || savedAt > now + 60L * 60L * 1000L) {
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
            partial = savedPartial;
            Log.i(TAG, "XMLTV EPG: кэш, " + parsed.size() + " каналов");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "XMLTV EPG: не удалось прочитать кэш", e);
            return false;
        } finally {
            closeQuietly(reader);
        }
    }

    private static Map<String, String> buildCanonicalMap(List<Channel> channels) {
        // Ключи сопоставления: точное название приоритетнее «примерного».
        Map<String, String> canonicalByKey = new HashMap<String, String>();
        Set<String> seen = new java.util.HashSet<String>();
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
        return canonicalByKey;
    }

    private static boolean parseAndCache(Context context, List<Channel> channels,
                                         String signature, long now) {
        if (channels == null || channels.isEmpty()) {
            return false;
        }

        Map<String, String> canonicalByKey = buildCanonicalMap(channels);
        if (canonicalByKey.isEmpty()) {
            return false;
        }

        long startedAt = System.currentTimeMillis();

        Map<String, String> idToCanonical = new HashMap<String, String>();
        Map<String, List<EpgProgram>> parsed = new HashMap<String, List<EpgProgram>>();
        boolean complete = false;

        // При смене плейлиста старые данные не должны смешиваться с новыми.
        // При обычном обновлении показываем прошлую программу, пока не готова новая.
        if (!signature.equals(loadedSignature)) {
            PROGRAMS.clear();
        }

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

            scan(input, canonicalByKey, idToCanonical, parsed, now);
            complete = true;
        } catch (Exception e) {
            Log.w(TAG, "XMLTV EPG: не удалось загрузить", e);
        } finally {
            closeQuietly(input);
            if (response != null) {
                response.close();
            }
        }

        if (parsed.isEmpty()) {
            return false;
        }

        removeDuplicates(parsed);
        // Даже неполный разбор полезнее пустого экрана: сохраняем и показываем то,
        // что успели прочитать, а обновление повторим через 10 минут.
        writeCache(context, signature, now, parsed, !complete);

        PROGRAMS.clear();
        PROGRAMS.putAll(parsed);
        loadedSignature = signature;
        loadedAt = now;
        partial = !complete;
        Log.i(TAG, "XMLTV EPG: " + parsed.size() + " каналов за "
                + (System.currentTimeMillis() - startedAt) + " мс"
                + (complete ? "" : ", загрузка неполная"));
        return true;
    }

    /**
     * Один проход по XMLTV. Сначала собираются имена каналов (id → название),
     * затем читаются только передачи каналов из плейлиста.
     */
    private static void scan(InputStream input,
                             Map<String, String> canonicalByKey,
                             Map<String, String> idToCanonical,
                             Map<String, List<EpgProgram>> parsed, long now)
            throws IOException {
        Source source = new Source(input);
        Bytes tag = new Bytes(320);
        Bytes block = new Bytes(1024);

        int section = source.nextSection();
        while (section == Source.SECTION_CHANNEL) {
            source.readTag(tag);
            String id = asciiAttribute(tag, ATTR_ID);
            if (id != null && !isSelfClosing(tag)) {
                source.readUntil(END_CHANNEL, block);
                String canonical = matchDisplayNames(block, canonicalByKey);
                if (canonical != null) {
                    idToCanonical.put(id, canonical);
                }
            }
            section = source.nextSection();
        }

        if (idToCanonical.isEmpty() || section != Source.SECTION_PROGRAMME) {
            return;
        }

        Set<String> done = new java.util.HashSet<String>();
        String previousId = null;
        String previousWanted = null;
        byte[] lastIdBytes = new byte[128];
        int lastIdLength = -1;
        int wanted = idToCanonical.size();

        while (section == Source.SECTION_PROGRAMME) {
            source.readTag(tag);
            boolean emptyTag = isSelfClosing(tag);

            int start = tag.indexOf(CHANNEL_ATTR, 0);
            if (start < 0) {
                if (!emptyTag) {
                    source.skipTo(END_PROGRAMME);
                }
                section = source.nextSection();
                continue;
            }
            int from = start + CHANNEL_ATTR.length;
            int to = tag.indexOf(QUOTE, from);
            if (to < 0 || to - from > lastIdBytes.length) {
                if (!emptyTag) {
                    source.skipTo(END_PROGRAMME);
                }
                section = source.nextSection();
                continue;
            }
            int idLength = to - from;
            boolean sameId = idLength == lastIdLength
                    && equalsBytes(tag.data, from, lastIdBytes, 0, idLength);
            String id;
            if (sameId) {
                id = previousId;
            } else {
                id = new String(tag.data, from, idLength, StandardCharsets.UTF_8);
                System.arraycopy(tag.data, from, lastIdBytes, 0, idLength);
                lastIdLength = idLength;

                // Файл отсортирован по каналам: как только начался следующий канал,
                // предыдущий прочитан целиком. Уже готовые каналы сразу отдаём
                // интерфейсу, не дожидаясь конца файла.
                if (previousId != null && previousWanted != null) {
                    done.add(previousId);
                    publishProgress(previousWanted, parsed);
                    if (done.size() >= wanted) {
                        break;
                    }
                }
                previousId = id;
                previousWanted = idToCanonical.get(id);
            }

            if (previousWanted == null || emptyTag) {
                if (!emptyTag) {
                    source.skipTo(END_PROGRAMME);
                }
                section = source.nextSection();
                continue;
            }

            long programmeStart = parseTime(asciiAttribute(tag, ATTR_START));
            long programmeStop = parseTime(asciiAttribute(tag, ATTR_STOP));
            boolean keep = programmeStop > programmeStart
                    && programmeStop > now - KEEP_PAST_MS
                    && programmeStart < now + KEEP_FUTURE_MS;

            if (!keep) {
                source.skipTo(END_PROGRAMME);
            } else {
                source.readUntil(END_PROGRAMME, block);
                String title = extractTitle(block);
                if (title != null && !title.isEmpty()) {
                    List<EpgProgram> list = parsed.get(previousWanted);
                    if (list == null) {
                        list = new ArrayList<EpgProgram>();
                        parsed.put(previousWanted, list);
                    }
                    list.add(new EpgProgram(programmeStart, programmeStop, title, "", 0));
                }
            }

            section = source.nextSection();
        }
    }

    private static String matchDisplayNames(Bytes block, Map<String, String> canonicalByKey) {
        String text = block.utf8();
        int index = 0;
        while (true) {
            int open = text.indexOf("<display-name", index);
            if (open < 0) {
                return null;
            }
            int gt = text.indexOf('>', open);
            int end = gt < 0 ? -1 : text.indexOf("</display-name>", gt);
            if (gt < 0 || end < 0) {
                return null;
            }
            String name = unescape(text.substring(gt + 1, end)).trim();
            if (!name.isEmpty()) {
                String canonical = canonicalFor(name, canonicalByKey);
                if (canonical != null) {
                    return canonical;
                }
            }
            index = end + 1;
        }
    }

    private static String extractTitle(Bytes block) {
        int open = block.indexOf(TITLE_TAG, 0);
        if (open < 0) {
            return null;
        }
        int gt = block.indexOf(GT, open);
        if (gt < 0) {
            return null;
        }
        int end = block.indexOf(END_TITLE, gt);
        if (end < 0) {
            return null;
        }
        return unescape(block.utf8(gt + 1, end - gt - 1));
    }

    private static String unescape(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = value.indexOf(';', i + 1);
            if (semi < 0) {
                out.append(c);
                i++;
                continue;
            }
            String entity = value.substring(i + 1, semi);
            if ("amp".equals(entity)) {
                out.append('&');
            } else if ("lt".equals(entity)) {
                out.append('<');
            } else if ("gt".equals(entity)) {
                out.append('>');
            } else if ("quot".equals(entity)) {
                out.append('"');
            } else if ("apos".equals(entity)) {
                out.append('\'');
            } else if ("nbsp".equals(entity)) {
                out.append(' ');
            } else if (entity.startsWith("#")) {
                try {
                    int code = entity.startsWith("#x") || entity.startsWith("#X")
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1));
                    out.appendCodePoint(code);
                } catch (Exception e) {
                    out.append('&').append(entity).append(';');
                }
            } else {
                out.append('&').append(entity).append(';');
            }
            i = semi + 1;
        }
        return out.toString();
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

    private static final ThreadLocal<Calendar> XML_CALENDAR =
            new ThreadLocal<Calendar>() {
                @Override
                protected Calendar initialValue() {
                    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"),
                            Locale.US);
                    calendar.setLenient(false);
                    return calendar;
                }
            };

    /** «20261019210000 +0300» → миллисекунды эпохи (UTC). */
    private static long parseTime(String value) {
        if (value == null || value.length() < 14) {
            return 0;
        }
        try {
            int year = digits(value, 0, 4);
            int month = digits(value, 4, 2);
            int day = digits(value, 6, 2);
            int hour = digits(value, 8, 2);
            int minute = digits(value, 10, 2);
            int second = digits(value, 12, 2);

            int offsetMinutes = 0;
            int i = 14;
            while (i < value.length()
                    && (value.charAt(i) == ' ' || value.charAt(i) == 'T')) {
                i++;
            }
            if (i < value.length()
                    && (value.charAt(i) == '+' || value.charAt(i) == '-')) {
                boolean negative = value.charAt(i) == '-';
                int offsetFrom = i + 1;
                int offsetHour = digits(value, offsetFrom, 2);
                int offsetMinute = 0;
                if (offsetFrom + 2 < value.length() && value.charAt(offsetFrom + 2) == ':') {
                    offsetMinute = digits(value, offsetFrom + 3, 2);
                } else if (offsetFrom + 4 <= value.length()) {
                    offsetMinute = digits(value, offsetFrom + 2, 2);
                }
                offsetMinutes = (offsetHour * 60 + offsetMinute) * (negative ? -1 : 1);
            } else if (i < value.length() && value.charAt(i) == 'Z') {
                offsetMinutes = 0;
            }

            Calendar calendar = XML_CALENDAR.get();
            calendar.clear();
            calendar.set(year, month - 1, day, hour, minute, second);
            return calendar.getTimeInMillis() - offsetMinutes * 60000L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int digits(String value, int from, int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            char c = value.charAt(from + i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("не число");
            }
            result = result * 10 + (c - '0');
        }
        return result;
    }

    private static boolean equalsBytes(byte[] a, int aFrom, byte[] b, int bFrom, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFrom + i] != b[bFrom + i]) {
                return false;
            }
        }
        return true;
    }

    private static final Comparator<EpgProgram> BY_START = new Comparator<EpgProgram>() {
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
    };

    /**
     * Отдаёт интерфейсу уже разобранный канал, пока остальные ещё читаются.
     * Список копируется: сканер продолжит дописывать рабочий список.
     */
    private static void publishProgress(String canonical,
                                        Map<String, List<EpgProgram>> parsed) {
        List<EpgProgram> list = parsed.get(canonical);
        if (list == null || list.isEmpty()) {
            return;
        }
        PROGRAMS.put(canonical, sortedUnique(list));
    }

    /** Сортировка по времени и удаление дублей; исходный список не меняется. */
    private static List<EpgProgram> sortedUnique(List<EpgProgram> list) {
        List<EpgProgram> sorted = new ArrayList<EpgProgram>(list);
        Collections.sort(sorted, BY_START);
        List<EpgProgram> unique = new ArrayList<EpgProgram>(sorted.size());
        EpgProgram previous = null;
        for (EpgProgram program : sorted) {
            if (previous != null && previous.start == program.start
                    && previous.title.equals(program.title)) {
                continue;
            }
            unique.add(program);
            previous = program;
        }
        return unique;
    }

    private static void removeDuplicates(Map<String, List<EpgProgram>> parsed) {
        for (Map.Entry<String, List<EpgProgram>> entry : parsed.entrySet()) {
            entry.setValue(sortedUnique(entry.getValue()));
        }
    }

    private static void writeCache(Context context, String signature, long now,
                                   Map<String, List<EpgProgram>> parsed, boolean partialLoad) {
        File file = new File(context.getCacheDir(), CACHE_NAME);
        File temp = new File(context.getCacheDir(), CACHE_NAME + ".tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(temp), "UTF-8"), 1 << 14);
            writer.write("# ");
            writer.write(Integer.toString(CACHE_VERSION));
            writer.write(' ');
            writer.write(signature);
            writer.write(' ');
            writer.write(Long.toString(now));
            writer.write(' ');
            writer.write(partialLoad ? '1' : '0');
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
            return new GZIPInputStream(pushback, 1 << 16);
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

    // ------------------------------------------------------------------
    // Потоковый сканер XML: без создания объектов на каждый тег.
    // ------------------------------------------------------------------

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final byte[] TAG_CHANNEL = ascii("channel");
    private static final byte[] TAG_PROGRAMME = ascii("programme");
    private static final byte[] END_CHANNEL = ascii("</channel>");
    private static final byte[] END_PROGRAMME = ascii("</programme>");
    private static final byte[] CHANNEL_ATTR = ascii("channel=\"");
    private static final byte[] ATTR_ID = ascii("id=\"");
    private static final byte[] ATTR_START = ascii("start=\"");
    private static final byte[] ATTR_STOP = ascii("stop=\"");
    private static final byte[] TITLE_TAG = ascii("<title");
    private static final byte[] END_TITLE = ascii("</title>");
    private static final byte[] GT = ascii(">");
    private static final byte[] QUOTE = ascii("\"");

    /** Буфер для накопления фрагмента документа. */
    private static final class Bytes {
        byte[] data;
        int size;

        Bytes(int capacity) {
            data = new byte[capacity];
        }

        void reset() {
            size = 0;
        }

        void add(byte value) {
            if (size == data.length) {
                grow(1);
            }
            data[size++] = value;
        }

        void add(byte[] source, int offset, int length) {
            if (size + length > data.length) {
                grow(length);
            }
            System.arraycopy(source, offset, data, size, length);
            size += length;
        }

        private void grow(int extra) {
            int capacity = data.length;
            while (capacity < size + extra) {
                capacity *= 2;
            }
            data = Arrays.copyOf(data, capacity);
        }

        int indexOf(byte[] needle, int from) {
            int last = size - needle.length;
            outer:
            for (int i = Math.max(0, from); i <= last; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (data[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        String utf8() {
            return new String(data, 0, size, StandardCharsets.UTF_8);
        }

        String utf8(int offset, int length) {
            return new String(data, offset, length, StandardCharsets.UTF_8);
        }

        String ascii(int offset, int length) {
            return new String(data, offset, length, StandardCharsets.ISO_8859_1);
        }
    }

    /** Потоковое чтение с поиском подстрок без промежуточных строк. */
    private static final class Source {

        static final int SECTION_CHANNEL = 0;
        static final int SECTION_PROGRAMME = 1;
        static final int SECTION_END = -1;

        private final InputStream in;
        private final byte[] buf = new byte[1 << 16];
        private int pos;
        private int end;

        Source(InputStream in) {
            this.in = in;
        }

        private boolean fill() throws IOException {
            if (pos > 0) {
                int remaining = end - pos;
                if (remaining > 0) {
                    System.arraycopy(buf, pos, buf, 0, remaining);
                }
                pos = 0;
                end = remaining;
            }
            while (end < buf.length) {
                int read = in.read(buf, end, buf.length - end);
                if (read <= 0) {
                    return false;
                }
                end += read;
            }
            return true;
        }

        private boolean ensure(int count) throws IOException {
            while (end - pos < count) {
                if (!fill()) {
                    return false;
                }
            }
            return true;
        }

        /** Читает до '>' включительно; содержимое тега складывает в out. */
        void readTag(Bytes out) throws IOException {
            out.reset();
            while (true) {
                if (pos >= end && !fill()) {
                    throw new IOException("неожиданный конец документа");
                }
                byte value = buf[pos++];
                if (value == '>') {
                    return;
                }
                out.add(value);
            }
        }

        /** Ищет ближайший <channel> или <programme>, съедая найденный тег. */
        int nextSection() throws IOException {
            while (seek((byte) '<')) {
                if (startsWithName(TAG_CHANNEL)) {
                    return SECTION_CHANNEL;
                }
                if (startsWithName(TAG_PROGRAMME)) {
                    return SECTION_PROGRAMME;
                }
            }
            return SECTION_END;
        }

        private boolean seek(byte value) throws IOException {
            while (true) {
                if (pos >= end && !fill()) {
                    return false;
                }
                if (buf[pos++] == value) {
                    return true;
                }
            }
        }

        private boolean startsWith(byte[] needle) throws IOException {
            if (!ensure(needle.length)) {
                return false;
            }
            for (int i = 0; i < needle.length; i++) {
                if (buf[pos + i] != needle[i]) {
                    return false;
                }
            }
            pos += needle.length;
            return true;
        }

        /** Совпадает ли имя элемента; следующий символ должен быть разделителем. */
        private boolean startsWithName(byte[] name) throws IOException {
            if (!ensure(name.length + 1)) {
                return false;
            }
            for (int i = 0; i < name.length; i++) {
                if (buf[pos + i] != name[i]) {
                    return false;
                }
            }
            byte next = buf[pos + name.length];
            if (next != ' ' && next != '\t' && next != '\r' && next != '\n'
                    && next != '>' && next != '/') {
                return false;
            }
            pos += name.length;
            return true;
        }

        /**
         * Ищет подстроку. Все искомые строки не содержат своего первого символа
         * внутри, поэтому достаточно простого счётчика совпадения.
         */
        boolean skipTo(byte[] needle) throws IOException {
            int length = needle.length;
            int matched = 0;
            while (true) {
                if (pos >= end && !fill()) {
                    return false;
                }
                byte value = buf[pos++];
                if (value == needle[matched]) {
                    if (++matched == length) {
                        return true;
                    }
                } else {
                    matched = value == needle[0] ? 1 : 0;
                }
            }
        }

        /** Как {@link #skipTo}, но найденное содержимое складывает в out. */
        void readUntil(byte[] needle, Bytes out) throws IOException {
            out.reset();
            int length = needle.length;
            int matched = 0;
            while (true) {
                if (pos >= end && !fill()) {
                    throw new IOException("неожиданный конец документа");
                }
                byte value = buf[pos++];
                if (value == needle[matched]) {
                    if (++matched == length) {
                        return;
                    }
                } else {
                    if (matched > 0) {
                        out.add(needle, 0, matched);
                        matched = 0;
                    }
                    if (value == needle[0]) {
                        matched = 1;
                    } else {
                        out.add(value);
                    }
                }
            }
        }
    }

    private static String asciiAttribute(Bytes tag, byte[] name) {
        int start = tag.indexOf(name, 0);
        if (start < 0) {
            return null;
        }
        int from = start + name.length;
        int to = tag.indexOf(QUOTE, from);
        if (to < 0) {
            return null;
        }
        return tag.ascii(from, to - from);
    }

    /** Тег вида «programme ... /» закрывается сразу, без содержимого. */
    private static boolean isSelfClosing(Bytes tag) {
        return tag.size > 0 && tag.data[tag.size - 1] == '/';
    }
}
