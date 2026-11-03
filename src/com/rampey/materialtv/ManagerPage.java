package com.rampey.materialtv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Launcher Manager: every launcher setting on one page, the way Android TV's own settings work. Categories on the left
 * (moving focus onto one shows its rows), rows on the right with their current value; OK on a row runs its action.
 * The page only lays things out; what each row does lives in HomeActivity.
 */
final class ManagerPage extends LinearLayout {

    static final class Row {
        final String title;
        final Supplier<String> value;
        final Runnable action;

        Row(String title, Supplier<String> value, Runnable action) {
            this.title = title;
            this.value = value;
            this.action = action;
        }
    }

    static final class Section {
        final String name;
        final List<Row> rows = new ArrayList<>();

        Section(String name) {
            this.name = name;
        }

        Section row(String title, Supplier<String> value, Runnable action) {
            rows.add(new Row(title, value, action));
            return this;
        }
    }

    /** Text in the launcher's own fonts and the colors of the moment. */
    interface Style {
        TextView text(String s, int sp, int color, int weight);

        Palette palette();
    }

    private final Style style;
    private final TextView title;
    private final LinearLayout rail;
    private final LinearLayout rows;
    private final ScrollView rowScroll;
    private final List<TextView> railItems = new ArrayList<>();
    private List<Section> sections = new ArrayList<>();
    private int current = -1;

    ManagerPage(Context context, Style style) {
        super(context);
        this.style = style;
        setOrientation(VERTICAL);
        setClipChildren(false);
        title = style.text("Cài đặt giao diện", 34, style.palette().onSurface, 760);
        title.setPadding(dp(4), dp(20), 0, dp(14));
        addView(title);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(HORIZONTAL);
        body.setClipChildren(false);
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView railScroll = new ScrollView(context);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setClipToPadding(false);
        railScroll.setPadding(0, dp(6), dp(6), dp(6));
        rail = new LinearLayout(context);
        rail.setOrientation(VERTICAL);
        rail.setClipChildren(false);
        railScroll.addView(rail);
        body.addView(railScroll, new LayoutParams(dp(230), LayoutParams.MATCH_PARENT));

        rowScroll = new ScrollView(context);
        rowScroll.setVerticalScrollBarEnabled(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(dp(18), dp(6), dp(10), dp(24));
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        rows.setClipChildren(false);
        rowScroll.addView(rows);
        body.addView(rowScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
    }

    void setTitle(String text) {
        title.setText(text);
    }

    /** New sections (same names keep the selected category); values are read again. */
    void show(List<Section> next) {
        boolean sameNames = next.size() == sections.size();
        for (int i = 0; sameNames && i < next.size(); i++) sameNames = next.get(i).name.equals(sections.get(i).name);
        sections = next;
        if (!sameNames) buildRail();
        selectSection(current < 0 || current >= sections.size() ? 0 : current, true);
    }

    /** Focus the selected category, as when the page opens. */
    void focusRail() {
        if (current >= 0 && current < railItems.size()) railItems.get(current).requestFocus();
    }

    private void buildRail() {
        rail.removeAllViews();
        railItems.clear();
        current = -1;
        for (int i = 0; i < sections.size(); i++) {
            final int index = i;
            TextView item = style.text(sections.get(i).name, 17, style.palette().onSurfaceVariant, 600);
            item.setId(View.generateViewId());
            item.setPadding(dp(20), dp(13), dp(20), dp(13));
            item.setFocusable(true);
            item.setClickable(true);
            item.setSingleLine(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(26));
            bg.setColor(Color.TRANSPARENT);
            item.setBackground(bg);
            item.setOnFocusChangeListener((v, focused) -> {
                if (focused && current != index) selectSection(index, false);
                styleRail();
            });
            item.setOnClickListener(v -> {
                if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            });
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            rail.addView(item, lp);
            railItems.add(item);
        }
    }

    private void selectSection(int index, boolean keepRow) {
        int focusedRow = -1;
        if (keepRow) {
            for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).hasFocus()) focusedRow = i;
        }
        current = index;
        rows.removeAllViews();
        if (index >= sections.size()) return;
        int railId = railItems.get(index).getId();
        for (Row r : sections.get(index).rows) rows.addView(rowView(r, railId), rowParams());
        if (!keepRow) rowScroll.scrollTo(0, 0);
        styleRail();
        if (focusedRow >= 0 && focusedRow < rows.getChildCount()) rows.getChildAt(focusedRow).requestFocus();
    }

    private LayoutParams rowParams() {
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private View rowView(Row r, int railId) {
        Palette p = style.palette();
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(22), dp(15), dp(22), dp(15));
        row.setFocusable(true);
        row.setClickable(true);
        row.setNextFocusLeftId(railId);   // back to the category this row belongs to, not the nearest one
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        row.setBackground(bg);
        TextView name = style.text(r.title, 17, p.onSurface, 600);
        name.setSingleLine(true);
        row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView value = style.text("", 15, p.onSurfaceVariant, 500);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setMaxWidth(dp(320));
        value.setGravity(Gravity.END);
        String v = r.value != null ? r.value.get() : null;
        value.setText(v != null ? v : "");
        row.addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        styleRow(row, bg, name, value, false);
        row.setOnFocusChangeListener((v1, focused) -> {
            styleRow(row, bg, name, value, focused);
            row.animate().scaleX(focused ? 1.02f : 1f).scaleY(focused ? 1.02f : 1f)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
        });
        row.setOnClickListener(v1 -> r.action.run());
        return row;
    }

    private void styleRow(View row, GradientDrawable bg, TextView name, TextView value, boolean focused) {
        Palette p = style.palette();
        bg.setColor(focused ? p.inverseSurface : (p.container & 0x00FFFFFF) | 0x99000000);
        name.setTextColor(focused ? p.inverseOnSurface : p.onSurface);
        value.setTextColor(focused ? p.inverseOnSurface : p.onSurfaceVariant);
    }

    private void styleRail() {
        Palette p = style.palette();
        title.setTextColor(p.onSurface);
        for (int i = 0; i < railItems.size(); i++) {
            TextView t = railItems.get(i);
            boolean focused = t.hasFocus();
            boolean on = i == current;
            int fill = focused ? p.inverseSurface : on ? p.primary : Color.TRANSPARENT;
            int ink = focused ? p.inverseOnSurface : on ? p.onPrimary : p.onSurfaceVariant;
            ((GradientDrawable) t.getBackground()).setColor(fill);
            t.setTextColor(ink);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
