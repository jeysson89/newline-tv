package com.newline.tv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.newline.tv.R;
import com.newline.tv.data.Channel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Список каналов: плитки или компактные строки, с навигацией по пульту. */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelHolder> {

    public interface Listener {
        void onChannelClick(Channel channel);
    }

    /** Строка канала показана — можно подгрузить мини-телепрограмму. */
    public interface NowListener {
        void onChannelVisible(Channel channel);
    }

    private static final int TYPE_CARD = 0;
    private static final int TYPE_COMPACT = 1;

    private final List<Channel> items = new ArrayList<>();
    private final Map<String, String> nowTexts = new HashMap<>();
    private final Listener listener;

    private NowListener nowListener;
    private boolean compact;

    /**
     * Прозрачность плиток для оверлея поверх видео: -1 — обычный вид,
     * иначе альфа фона 0..255 (синхронно с ползунком прозрачности в настройках).
     */
    private int overlayAlpha = -1;

    public ChannelAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setNowListener(NowListener nowListener) {
        this.nowListener = nowListener;
    }

    /** Переключает вид: плитки или компактные строки. */
    public void setCompact(boolean compact) {
        if (this.compact == compact) {
            return;
        }
        this.compact = compact;
        notifyDataSetChanged();
    }

    public boolean isCompact() {
        return compact;
    }

    public void setItems(List<Channel> channels) {
        items.clear();
        if (channels != null) {
            items.addAll(channels);
        }
        notifyDataSetChanged();
    }

    /** Задаёт прозрачность фона плиток (для полупрозрачного списка поверх видео). */
    public void setOverlayAlpha(int alpha) {
        int value = Math.max(0, Math.min(255, alpha));
        if (overlayAlpha == value) {
            return;
        }
        overlayAlpha = value;
        notifyDataSetChanged();
    }

    /** Текущая передача канала для мини-EPG; пустая строка — данных нет. */
    public void setNow(Channel channel, String text) {
        String key = nowKey(channel);
        String value = text == null ? "" : text;
        String previous = nowTexts.get(key);
        if (previous != null && previous.equals(value)) {
            return;
        }
        nowTexts.put(key, value);
        int index = indexOf(channel);
        if (index >= 0) {
            notifyItemChanged(index);
        }
    }

    public String getNow(Channel channel) {
        String value = nowTexts.get(nowKey(channel));
        return value == null ? "" : value;
    }

    private int indexOf(Channel channel) {
        for (int i = 0; i < items.size(); i++) {
            Channel item = items.get(i);
            if (item.url.equals(channel.url) && item.name.equals(channel.name)) {
                return i;
            }
        }
        return -1;
    }

    private static String nowKey(Channel channel) {
        return channel.url + "\n" + channel.name;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return compact ? TYPE_COMPACT : TYPE_CARD;
    }

    @NonNull
    @Override
    public ChannelHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_COMPACT
                ? R.layout.item_channel_compact
                : R.layout.item_channel;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ChannelHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ChannelHolder holder, int position) {
        final Channel channel = items.get(position);

        holder.name.setText(channel.name);
        holder.initials.setText(channel.initials());

        if (holder.group != null) {
            if (channel.group.isEmpty()) {
                holder.group.setVisibility(View.GONE);
            } else {
                holder.group.setVisibility(View.VISIBLE);
                holder.group.setText(channel.group);
            }
        }

        if (holder.now != null) {
            String now = nowTexts.get(nowKey(channel));
            if (now == null || now.isEmpty()) {
                // В компактной строке до загрузки телепрограммы показываем группу канала.
                now = holder.group == null ? channel.group : "";
            }
            if (now == null || now.isEmpty()) {
                holder.now.setVisibility(View.GONE);
            } else {
                holder.now.setText(now);
                holder.now.setVisibility(View.VISIBLE);
            }
        }

        applyBackground(holder);

        holder.logo.setTag(channel.logoUrl);
        holder.logo.setImageDrawable(null);
        holder.logo.setVisibility(View.GONE);
        holder.initials.setVisibility(View.VISIBLE);

        if (!channel.logoUrl.isEmpty()) {
            ImageLoader.load(channel.logoUrl, new ImageLoader.Callback() {
                @Override
                public void onLoaded(Bitmap bitmap) {
                    if (bitmap != null && channel.logoUrl.equals(holder.logo.getTag())) {
                        holder.logo.setImageBitmap(bitmap);
                        holder.logo.setVisibility(View.VISIBLE);
                        holder.initials.setVisibility(View.GONE);
                    }
                }
            });
        }

        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onChannelClick(channel);
            }
        });

        holder.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float scale = hasFocus ? 1.05f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(140).start();
            }
        });

        if (nowListener != null) {
            nowListener.onChannelVisible(channel);
        }
    }

    /**
     * В обычном режиме берём фон из ресурсов, а в оверлее строим полупрозрачный фон
     * того же цвета темы — чтобы плитки гасли вместе с фоном списка.
     */
    private void applyBackground(ChannelHolder holder) {
        if (overlayAlpha < 0) {
            holder.itemView.setBackgroundResource(R.drawable.bg_channel_card);
            holder.logoBox.setBackgroundResource(R.drawable.bg_logo_box);
            return;
        }

        Context context = holder.itemView.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int alpha = overlayAlpha;

        int card = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.card), alpha);
        int cardFocused = ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.card_focused), alpha);
        int divider = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.divider), alpha);
        // Рамку фокуса оставляем заметной даже при сильной прозрачности.
        int focusRing = ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.focus_ring), Math.max(alpha, 180));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(card);
        normal.setCornerRadius(16f * density);
        normal.setStroke(Math.round(1f * density), divider);

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(cardFocused);
        focused.setCornerRadius(16f * density);
        focused.setStroke(Math.round(3f * density), focusRing);

        StateListDrawable cardDrawable = new StateListDrawable();
        cardDrawable.addState(new int[]{android.R.attr.state_focused}, focused);
        cardDrawable.addState(new int[]{android.R.attr.state_pressed}, focused);
        cardDrawable.addState(new int[]{}, normal);
        holder.itemView.setBackground(cardDrawable);

        GradientDrawable box = new GradientDrawable();
        box.setColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.logo_box_bg), alpha));
        box.setCornerRadius(10f * density);
        box.setStroke(Math.round(1f * density),
                ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.logo_box_stroke), alpha));
        holder.logoBox.setBackground(box);
    }

    static class ChannelHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView group;
        final TextView now;
        final TextView initials;
        final ImageView logo;
        final View logoBox;

        ChannelHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.channelName);
            group = itemView.findViewById(R.id.channelGroup);
            now = itemView.findViewById(R.id.channelNow);
            initials = itemView.findViewById(R.id.channelInitials);
            logo = itemView.findViewById(R.id.channelLogo);
            logoBox = itemView.findViewById(R.id.channelLogoBox);
        }
    }
}
