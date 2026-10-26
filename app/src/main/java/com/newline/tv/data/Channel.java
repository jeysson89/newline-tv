package com.newline.tv.data;

/** Один канал из плейлиста оператора. */
public class Channel {

    public final String id;
    public final String name;
    public final String url;
    public final String logoUrl;
    public final String group;
    /** ID канала в EPG портала (для телепрограммы), 0 — если неизвестен. */
    public final int epgId;

    public Channel(String id, String name, String url, String logoUrl, String group) {
        this(id, name, url, logoUrl, group, 0);
    }

    public Channel(String id, String name, String url, String logoUrl, String group, int epgId) {
        this.id = id;
        this.name = name == null ? "" : name.trim();
        this.url = url == null ? "" : url.trim();
        this.logoUrl = logoUrl == null ? "" : logoUrl.trim();
        this.group = group == null ? "" : group.trim();
        this.epgId = epgId;
    }

    public String initials() {
        String source = name.trim();
        if (source.isEmpty()) {
            return "TV";
        }
        String[] parts = source.split("[\\s\\-–—|·•]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            char first = part.charAt(0);
            if (!Character.isLetterOrDigit(first)) {
                continue;
            }
            sb.append(Character.toUpperCase(first));
            if (sb.length() == 2) {
                break;
            }
        }
        if (sb.length() == 0) {
            sb.append(Character.toUpperCase(source.charAt(0)));
        }
        return sb.toString();
    }
}
