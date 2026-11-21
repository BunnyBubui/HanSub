package th.hansub.live;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The film-style entrance and page change from Lumora: two black bars close like a cut, a thin line of light
 * crosses the middle with the name under it, then the bars open on the next scene.
 */
final class Cine {
    private Cine() {}

    /** One curtain lives on the window's top layer for the length of a move, then removes itself. */
    static final class Curtain {
        private final Activity activity;
        private final ViewGroup decor;
        private final int half;
        private final PathInterpolator ease = new PathInterpolator(0.65f, 0f, 0.35f, 1f);
        private final View top, bottom, line;
        private final TextView title;
        private boolean shown;

        Curtain(Activity a, String name) {
            activity = a;
            decor = (ViewGroup) a.getWindow().getDecorView();
            half = a.getResources().getDisplayMetrics().heightPixels / 2 + 4;
            top = new View(a);
            top.setBackgroundColor(Color.BLACK);
            bottom = new View(a);
            bottom.setBackgroundColor(Color.BLACK);
            line = new View(a);
            line.setBackgroundColor(Glass.TEXT);
            line.setScaleX(0f);
            line.setAlpha(0f);
            title = new TextView(a);
            title.setText(name);
            title.setTextColor(Glass.TEXT);
            title.setTextSize(12);
            title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            title.setLetterSpacing(0.2f);
            title.setGravity(Gravity.CENTER);
            title.setAlpha(0f);
        }

        private void show() {
            if (shown) return;
            shown = true;
            decor.addView(top, new FrameLayout.LayoutParams(-1, half, Gravity.TOP));
            decor.addView(bottom, new FrameLayout.LayoutParams(-1, half, Gravity.BOTTOM));
            decor.addView(line, new FrameLayout.LayoutParams(-1,
                    Math.max(1, Math.round(activity.getResources().getDisplayMetrics().density)),
                    Gravity.CENTER_VERTICAL));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
            tp.topMargin = Glass.dp(activity, 26);
            decor.addView(title, tp);
        }

        void remove() {
            if (!shown) return;
            shown = false;
            decor.removeView(top);
            decor.removeView(bottom);
            decor.removeView(line);
            decor.removeView(title);
        }

        /** Bars fully shut at once, with no motion. */
        void shut() {
            show();
            top.setTranslationY(0f);
            bottom.setTranslationY(0f);
        }

        void close(long ms, Runnable end) {
            show();
            top.setTranslationY(-half);
            bottom.setTranslationY(half);
            top.animate().translationY(0f).setDuration(ms).setInterpolator(ease).start();
            bottom.animate().translationY(0f).setDuration(ms).setInterpolator(ease)
                    .withEndAction(end).start();
        }

        void open(long ms) {
            show();
            line.animate().alpha(0f).setDuration(ms / 2).start();
            title.animate().alpha(0f).setDuration(ms / 2).start();
            top.animate().translationY(-half).setDuration(ms).setInterpolator(ease).start();
            bottom.animate().translationY(half).setDuration(ms).setInterpolator(ease)
                    .withEndAction(this::remove).start();
        }

        /** The opening title: a line of light grows from the centre, the name spreads out under it. */
        void lineAndTitle(long lineMs, long titleMs) {
            line.setAlpha(0.95f);
            line.animate().scaleX(1f).setDuration(lineMs).setInterpolator(ease).start();
            title.animate().alpha(0.9f).setDuration(titleMs).start();
            ValueAnimator spread = ValueAnimator.ofFloat(0.2f, 0.55f);
            spread.setDuration(titleMs + 250);
            spread.setInterpolator(new PathInterpolator(0.16f, 1f, 0.3f, 1f));
            spread.addUpdateListener(a -> title.setLetterSpacing((float) a.getAnimatedValue()));
            spread.start();
        }
    }

    /** The first screen of the app: black, a line of light and the name, then the bars open. {@code reveal} runs as they begin to open. */
    static void intro(Activity a, String name, Runnable reveal) {
        Curtain c = new Curtain(a, name);
        c.shut();
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> {
            if (!a.isDestroyed()) c.lineAndTitle(180, 220);
        }, 60);
        h.postDelayed(() -> {
            if (a.isDestroyed()) return;
            reveal.run();
            c.open(380);
        }, 640);
    }

    /** Leaving a page: the bars shut, then {@code go} starts the next page. The returned curtain stays shut until it is opened. */
    static Curtain leave(Activity a, String name, Runnable go) {
        Curtain c = new Curtain(a, name);
        c.close(190, go);
        return c;
    }

    /** The next page's side: it starts behind shut bars, which open while its content rises. {@code reveal} runs as they open. */
    static void enter(Activity a, String name, Runnable reveal) {
        Curtain c = new Curtain(a, name);
        c.shut();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (a.isDestroyed()) return;
            reveal.run();
            c.open(320);
        }, 160);
    }
}
