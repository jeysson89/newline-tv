package com.newline.tv.data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор плейлистов M3U / M3U8. */
public final class M3uParser {

    private static final Pattern ATTR = Pattern.compile("([a-zA-Z0-9\\-_]+)=\"([^\"]*)\"");

    private M3uParser() {
    }

    /** Берёт название после последней запятой вне кавычек. */
    private static String nameAfterLastComma(String line) {
        int lastComma = -1;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                lastComma = i;
            }
        }
        if (lastComma < 0 || lastComma + 1 >= line.length()) {
            return null;
        }
        String name = line.substring(lastComma + 1).trim();
        return name.isEmpty() ? null : name;
    }

    public static List<Channel> parse(String body) {
        List<Channel> channels = new ArrayList<>();
        if (body == null) {
            return channels;
        }

        String[] lines = body.split("\\r?\\n");
        String pendingName = null;
        String pendingLogo = "";
        String pendingGroup = "";
        String pendingId = "";
        int autoNumber = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("#EXTINF")) {
                pendingName = null;
                pendingLogo = "";
                pendingGroup = "";
                pendingId = "";

                Matcher attrMatcher = ATTR.matcher(line);
                while (attrMatcher.find()) {
                    String key = attrMatcher.group(1).toLowerCase();
                    String value = attrMatcher.group(2);
                    if ("tvg-logo".equals(key) || "logo".equals(key)) {
                        pendingLogo = value;
                    } else if ("group-title".equals(key) || "group".equals(key)) {
                        pendingGroup = value;
                    } else if ("tvg-id".equals(key) || "channel-id".equals(key)) {
                        pendingId = value;
                    }
                }

                pendingName = nameAfterLastComma(line);
                continue;            }

            if (line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("http://") || line.startsWith("https://")
                    || line.startsWith("rtmp://") || line.startsWith("rtsp://")
                    || line.startsWith("udp://") || line.startsWith("rtp://")) {

                autoNumber++;
                String name = pendingName;
                if (name == null || name.isEmpty()) {
                    name = "Канал " + autoNumber;
                }
                String id = pendingId;
                if (id == null || id.isEmpty()) {
                    id = String.valueOf(autoNumber);
                }
                channels.add(new Channel(id, name, line, pendingLogo, pendingGroup));

                pendingName = null;
                pendingLogo = "";
                pendingGroup = "";
                pendingId = "";
            }
        }

        return channels;
    }
}
