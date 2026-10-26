package com.newline.tv.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.newline.tv.R;

/** Вертикальный контейнер, который не растягивается на всю ширину телевизора. */
public class MaxWidthLinearLayout extends LinearLayout {

    private final int maxWidth;

    public MaxWidthLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        maxWidth = context.getResources().getDimensionPixelSize(R.dimen.settings_max_width);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec);
        if (available > maxWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
