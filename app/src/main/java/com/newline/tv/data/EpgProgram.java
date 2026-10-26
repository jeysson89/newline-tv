package com.newline.tv.data;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Передача в телепрограмме канала. */
public class EpgProgram {

    private static final ThreadLocal<SimpleDateFormat> TIME =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm", Locale.getDefault());
                }
            };

    public final long start;
    public final long stop;
    public final String title;
    public final String description;
    public final int ageLimit;

    public EpgProgram(long start, long stop, String title, String description, int ageLimit) {
        this.start = start;
        this.stop = stop;
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
        this.ageLimit = ageLimit;
    }

    public boolean isCurrent(long now) {
        return now >= start && now < stop;
    }

    /** «21:45–23:25» — время передачи в часовом поясе устройства. */
    public String timeRange() {
        return time(start) + "–" + time(stop);
    }

    static String time(long millis) {
        return TIME.get().format(new Date(millis));
    }
}
