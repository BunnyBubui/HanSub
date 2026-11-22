package th.hansub.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared look for every HanSub screen. It follows the "Graphite" theme of the Lumora camera app:
 * near-black page, charcoal glass panels with a soft light rim, one orange accent.
 * Colour and spring maths here is plain arithmetic, so unit tests can run it without Android.
 * Animation lives in {@link Motion}.
 */
final class Glass {
    private Glass() {}

    static final int WHITE = 0xffffffff, BLACK = 0xff000000;

    /** The four Graphite colours: accent, page, panel, text. */
    static final int ACCENT = 0xffff9f45, BG = 0xff0a0a0b, PANEL = 0xff1c1c1f, TEXT = 0xfff5f5f4;

    /** Colours worked out from the four above, with the same formulas Lumora uses. */
    static final int ACCENT_HI = mix(ACCENT, WHITE, 0.25f);
    static final int ACCENT_LO = mix(ACCENT, BLACK, 0.12f);
    static final int ON_ACCENT = mix(ACCENT, BLACK, 0.84f);
    static final int ACCENT_SOFT = alpha(ACCENT, 0x2e);
    static final int TEXT2 = alpha(TEXT, 0xb8);
    static final int TEXT3 = alpha(TEXT, 0x99);
    static final int FILL = alpha(PANEL, 0x8c);
    static final int FILL_STRONG = alpha(mix(PANEL, BG, 0.4f), 0xe0);
    static final int FILL_POP = alpha(mix(PANEL, BG, 0.15f), 0xf0);
    static final int BG_TOP = mix(BG, PANEL, 0.45f);
    static final int BG_DEEP = mix(BG, BLACK, 0.55f);
    static final int TINT_TOP = mix(PANEL, WHITE, 0.14f);
    static final int TINT_BOTTOM = mix(BG, BLACK, 0.5f);
    private static final int RIM_ALPHA = 36;

    /** Moves each colour channel of {@code c} toward {@code to} by {@code k}; keeps the alpha of {@code c}. */
    static int mix(int c, int to, float k) {
        return (c & 0xff000000) | (channel(c, to, k, 16) << 16) | (channel(c, to, k, 8) << 8) | channel(c, to, k, 0);
    }

    private static int channel(int c, int to, float k, int shift) {
        int from = (c >> shift) & 255, target = (to >> shift) & 255;
        return Math.max(0, Math.min(255, Math.round(from + (target - from) * k)));
    }

    /** {@code c} with its alpha replaced by {@code a} (0 to 255). */
    static int alpha(int c, int a) {
        return (c & 0xffffff) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private static float weight(int c) {
        int a = (c >>> 24) & 255;
        float lum = (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 765f;
        return (a > 0xc0 && lum > 0.8f) ? 0.06f : 0.32f;
    }

    /** Top and bottom colour of the vertical gradient every glass fill is drawn with. */
    static int top(int c) { return c == ACCENT ? ACCENT_HI : mix(c, TINT_TOP, weight(c)); }
    static int bottom(int c) { return c == ACCENT ? ACCENT_LO : mix(c, TINT_BOTTOM, weight(c) + 0.06f); }

    /**
     * Damped spring step response: 0 at {@code t = 0}, overshoots 1 and settles on it.
     * {@code zeta} is the damping (below 1 overshoots), {@code w} the speed. Lumora uses it for every morph.
     */
    static float spring(float t, float zeta, float w) {
        if (t <= 0f) return 0f;
        double wd = w * Math.sqrt(1 - zeta * zeta);
        return (float) (1 - Math.exp(-zeta * w * t) * (Math.cos(wd * t) + zeta * w / wd * Math.sin(wd * t)));
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Page background: a touch lighter at the top, fading to near black. */
    static GradientDrawable page() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG, BG_DEEP});
    }

    /** A glass panel: vertical gradient fill and one soft rim, bright at the top. Corners are 20% rounder than asked, like Lumora. */
    static Panel panel(Context c, int radiusDp, int fill) {
        return new Panel(fill, dp(c, radiusDp) * 1.2f, c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static TextView text(Context c, String value, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    static Btn button(Context c, String label, int fill, int textColor) {
        Btn b = new Btn(c);
        b.setText(label);
        b.setTextColor(textColor);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(0);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setBackground(panel(c, 14, fill));
        return b;
    }

    /** Changes a button's label and colours in place. */
    static void setButton(Btn b, String label, int fill, int textColor) {
        b.setText(label);
        b.setTextColor(textColor);
        b.setBackground(panel(b.getContext(), 14, fill));
    }

    /** A wide glass button with an icon and a label, such as the start/stop button. Restyle it with {@link #setAction}. */
    static LinearLayout action(Context c, int icon, String label, int fill, int ink) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setMinimumHeight(dp(c, 58));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        IconView glyph = new IconView(c, icon);
        b.addView(glyph, new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        TextView t = text(c, label, 16, ink, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(c, 10);
        b.addView(t, tp);
        setAction(b, icon, label, fill, ink);
        Motion.touchable(b);
        return b;
    }

    static void setAction(LinearLayout b, int icon, String label, int fill, int ink) {
        ((IconView) b.getChildAt(0)).setKind(icon);
        ((IconView) b.getChildAt(0)).setTint(ink);
        TextView t = (TextView) b.getChildAt(1);
        t.setText(label);
        t.setTextColor(ink);
        b.setBackground(panel(b.getContext(), 18, fill));
    }

    /** Small rounded label. {@code on} gives the orange tint, otherwise a quiet grey one. */
    static TextView chip(Context c, String label, boolean on) {
        TextView t = text(c, label, 12, TEXT2, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        setChip(t, label, on);
        return t;
    }

    static void setChip(TextView t, String label, boolean on) {
        t.setText(label);
        t.setTextColor(on ? ACCENT : TEXT2);
        t.setBackground(panel(t.getContext(), 10, on ? ACCENT_SOFT : FILL_STRONG));
    }

    /** Receives the system bar and display cutout sizes in pixels. */
    interface InsetListener {
        void onInsets(int left, int top, int right, int bottom);
    }

    /**
     * Pads the page by the status bar, navigation bar and display cutout, so content in landscape
     * never hides behind a side bar or a camera notch. Content still scrolls under the bars.
     */
    static void fitSystemBars(ScrollView page, InsetListener listener) {
        page.setClipToPadding(false);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            if (listener != null) listener.onInsets(left, top, right, bottom);
            return insets;
        });
        page.requestApplyInsets();
    }

    /**
     * A vertical column that never gets wider than {@code maxDp}. On a wide landscape screen the content
     * stays at a readable width instead of stretching edge to edge. Put it in a FrameLayout (or ScrollView)
     * with {@code Gravity.CENTER_HORIZONTAL} to centre it.
     */
    static final class Column extends LinearLayout {
        private final int maxPx;

        Column(Context c, int maxDp) {
            super(c);
            maxPx = dp(c, maxDp);
            setOrientation(VERTICAL);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (MeasureSpec.getSize(widthSpec) > maxPx) {
                widthSpec = MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** Button with a glass background: no Material shadow, dims when disabled, presses in on touch. */
    static final class Btn extends Button {
        Btn(Context c) {
            super(c);
            setAllCaps(false);
            setStateListAnimator(null);
            setElevation(0f);
            Motion.touchable(this);
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.45f);
        }
    }

    /** The glass drawable: gradient fill, soft rim, and a light that follows the finger while it is pressed. */
    static final class Panel extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private final int fill;
        private float radius;
        private final float density;
        private Shader fillShader, rimShader;
        private float shaderTop = Float.NaN, shaderHeight = -1f;
        private int layerAlpha = 255;
        private float touchX = -1f, touchY = -1f, touchAlpha = 0f;

        Panel(int fill, float radius, float density) {
            this.fill = fill;
            this.radius = radius;
            this.density = density;
        }

        float getRadius() { return radius; }
        void setRadius(float r) { radius = r; invalidateSelf(); }
        float getTouchAlpha() { return touchAlpha; }
        void setTouchAlpha(float a) { touchAlpha = a; invalidateSelf(); }

        void touch(float x, float y, float alpha) {
            touchX = x; touchY = y; touchAlpha = alpha;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            rect.set(getBounds());
            if (rect.width() <= 0 || rect.height() <= 0) return;
            float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            if (fillShader == null || shaderHeight != rect.height() || shaderTop != rect.top) {
                shaderHeight = rect.height();
                shaderTop = rect.top;
                fillShader = new LinearGradient(0f, rect.top, 0f, rect.bottom,
                        top(fill), bottom(fill), Shader.TileMode.CLAMP);
                int hi = Math.min(255, Math.round(RIM_ALPHA * 1.6f));
                int lo = Math.round(RIM_ALPHA * 0.25f);
                rimShader = new LinearGradient(0f, rect.top, 0f, rect.bottom,
                        new int[]{white(hi), white(lo), white(lo / 2)},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(fillShader);
            paint.setAlpha(layerAlpha);
            canvas.drawRoundRect(rect, r, r, paint);
            if (touchAlpha > 0.01f && touchX >= 0f) {
                canvas.save();
                clip.reset();
                clip.addRoundRect(rect, r, r, Path.Direction.CW);
                canvas.clipPath(clip);
                float glow = 110f * density;
                paint.setShader(new RadialGradient(touchX, touchY, glow,
                        ((int) (70 * touchAlpha) << 24) | 0xffd6aa, 0x00000000, Shader.TileMode.CLAMP));
                canvas.drawCircle(touchX, touchY, glow, paint);
                canvas.restore();
            }
            float stroke = density * 0.9f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setShader(rimShader);
            paint.setAlpha(layerAlpha);
            rect.inset(stroke / 2f, stroke / 2f);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setShader(null);
        }

        private static int white(int a) { return (a << 24) | 0xffffff; }

        @Override public void setAlpha(int alpha) { layerAlpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void getOutline(Outline outline) {
            Rect b = getBounds();
            outline.setRoundRect(b, Math.min(radius, Math.min(b.width(), b.height()) / 2f));
        }
    }
}
