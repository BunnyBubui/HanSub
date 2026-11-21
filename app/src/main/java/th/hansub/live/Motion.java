package th.hansub.live;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Lumora's motion language, ported: touch-following glass that presses in and springs back,
 * a "liquid" morph that grows a sheet out of the spot that was tapped, and the soft rise used when a page opens.
 * Everything stops when the phone has animations turned off.
 */
final class Motion {
    private Motion() {}

    static final PathInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    static final PathInterpolator SPRING = new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);
    static final PathInterpolator POP = new PathInterpolator(0.2f, 0.9f, 0.25f, 1.15f);
    static final PathInterpolator SOFT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);

    /** True when the person turned animations off (Developer options or battery saver), so nothing should move. */
    static boolean reduced(Context c) {
        return !ValueAnimator.areAnimatorsEnabled();
    }

    /** Soft haptic tap. The system's own touch-feedback setting still decides whether it is felt. */
    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** Where the last finger lifted, so a sheet or a new page can grow out of the button that was pressed. */
    static final class TapPoint {
        private static float x, y;
        private static long at;

        static void record(MotionEvent e) {
            x = e.getRawX();
            y = e.getRawY();
            at = SystemClock.uptimeMillis();
        }

        /** The tap in the coordinates of {@code v}, or null when no tap just happened. */
        static float[] inside(View v) {
            if (at == 0L || SystemClock.uptimeMillis() - at > 900L) return null;
            int[] l = new int[2];
            v.getLocationOnScreen(l);
            return new float[]{x - l[0], y - l[1]};
        }
    }

    /**
     * Touch-following glass: the view shrinks a little, leans toward the finger and lights up under it,
     * then springs back. It lets go when the finger starts to scroll. Never consumes the touch,
     * so clicks and scrolling keep working.
     */
    static void touchable(View view) {
        final float density = view.getResources().getDisplayMetrics().density;
        final float slop = 10 * density;
        final float[] state = new float[3]; // start x, start y, 1 while pressed
        view.setOnTouchListener((v, e) -> {
            if (!v.isEnabled()) return false;
            Glass.Panel g = v.getBackground() instanceof Glass.Panel ? (Glass.Panel) v.getBackground() : null;
            boolean reduced = reduced(v.getContext());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    state[0] = e.getRawX();
                    state[1] = e.getRawY();
                    state[2] = 1f;
                    if (!reduced) v.animate().scaleX(0.97f).scaleY(0.97f).setInterpolator(EASE).setDuration(140).start();
                    if (g != null) g.touch(e.getX(), e.getY(), 1f);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (state[2] == 1f) {
                        if (Math.hypot(e.getRawX() - state[0], e.getRawY() - state[1]) > slop) {
                            release(v, g, state);
                        } else if (!reduced) {
                            // The press shrinks the view, so a small lean stays inside its own bounds.
                            v.setTranslationX((e.getX() / Math.max(1, v.getWidth()) - 0.5f)
                                    * Math.min(4 * density, v.getWidth() * 0.02f));
                            v.setTranslationY((e.getY() / Math.max(1, v.getHeight()) - 0.5f)
                                    * Math.min(3 * density, v.getHeight() * 0.02f));
                            if (g != null) g.touch(e.getX(), e.getY(), 1f);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (state[2] == 1f) {
                        TapPoint.record(e);
                        tick(v);
                    }
                    release(v, g, state);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    release(v, g, state);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static void release(View v, Glass.Panel g, float[] state) {
        if (state[2] != 1f) return;
        state[2] = 0f;
        boolean reduced = reduced(v.getContext());
        v.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setInterpolator(SPRING).setDuration(reduced ? 0 : 560).start();
        if (g != null) {
            ValueAnimator fade = ValueAnimator.ofFloat(g.getTouchAlpha(), 0f);
            fade.setDuration(480);
            fade.addUpdateListener(a -> g.setTouchAlpha((float) a.getAnimatedValue()));
            fade.start();
        }
    }

    /** The soft entrance of one block: it fades in while it rises into place. */
    static void rise(View v, long delay, float dist, long ms) {
        if (reduced(v.getContext())) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(dist);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(ms).setInterpolator(SOFT).start();
    }

    /** A quick overshoot, used when a label or button changes state. */
    static void pop(View v) {
        if (reduced(v.getContext())) return;
        v.setScaleX(0.86f);
        v.setScaleY(0.86f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(POP).start();
    }

    // ---- Liquid: a view grows from a drop at the pivot into its shape and back --------------------

    private static final WeakHashMap<View, Animator> RUNNING = new WeakHashMap<>();
    private static final WeakHashMap<View, Float> RADII = new WeakHashMap<>();

    /**
     * Grows {@code v} out of ({@code pivotX}, {@code pivotY}) with a damped spring. The Y axis lags the X axis,
     * the corners start very round, and the children fade in after the first third.
     */
    static void show(View v, float pivotX, float pivotY, boolean sheet, Runnable end) {
        Animator running = RUNNING.get(v);
        if (running != null) running.cancel();
        v.setVisibility(View.VISIBLE);
        final Glass.Panel g = v.getBackground() instanceof Glass.Panel ? (Glass.Panel) v.getBackground() : null;
        Float stored = RADII.get(v);
        final float base = stored != null ? stored : (g != null ? g.getRadius() : 0f);
        RADII.put(v, base);
        final List<View> kids = children(v);
        if (reduced(v.getContext())) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setAlpha(1f);
            if (g != null) g.setRadius(base);
            for (View k : kids) {
                k.setAlpha(1f);
                k.setTranslationY(0f);
            }
            if (end != null) end.run();
            return;
        }
        v.setPivotX(pivotX);
        v.setPivotY(pivotY);
        final float dur = sheet ? 720f : 520f;
        final float s0x = sheet ? 0.14f : 0.3f, s0y = sheet ? 0.066f : 0.2f;
        final float zeta = sheet ? 0.6f : 0.68f, w = sheet ? 13.5f : 17f, lag = sheet ? 0.04f : 0.025f;
        final float lift = 8 * v.getResources().getDisplayMetrics().density;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) dur);
        a.setInterpolator(null);
        a.addUpdateListener(an -> {
            float f = an.getAnimatedFraction();
            float t = f * dur / 1000f;
            float px = Glass.spring(t, zeta, w);
            float py = Glass.spring(Math.max(0f, t - lag), zeta, w);
            v.setScaleX(s0x + (1 - s0x) * px);
            v.setScaleY(s0y + (1 - s0y) * py);
            v.setAlpha(Math.min(1f, sheet ? 0.9f + f * 0.4f : f * 5f));
            if (g != null) {
                g.setRadius(base * (1 + (sheet ? 30f : 14f) * (float) Math.pow(Math.max(0f, 1 - px), 2.2)));
            }
            float c = Math.max(0f, Math.min(1f, (f - 0.3f) / 0.7f));
            float ce = 1 - (float) Math.pow(1 - c, 3);
            for (View k : kids) {
                k.setAlpha(ce);
                k.setTranslationY(lift * (1 - ce));
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator an) { cancelled = true; }

            @Override public void onAnimationEnd(Animator an) {
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setAlpha(1f);
                if (g != null) g.setRadius(base);
                for (View k : kids) {
                    k.setAlpha(1f);
                    k.setTranslationY(0f);
                }
                if (!cancelled && end != null) end.run();
            }
        });
        // Start from the drop, so the first frame never flashes the view at full size.
        v.setScaleX(s0x);
        v.setScaleY(s0y);
        v.setAlpha(sheet ? 0.9f : 0f);
        for (View k : kids) k.setAlpha(0f);
        RUNNING.put(v, a);
        a.start();
    }

    /** The reverse of {@link #show}: shrinks back into the pivot and hides the view. */
    static void hide(View v, float pivotX, float pivotY, boolean sheet, Runnable end) {
        Animator running = RUNNING.get(v);
        if (running != null) running.cancel();
        final Glass.Panel g = v.getBackground() instanceof Glass.Panel ? (Glass.Panel) v.getBackground() : null;
        Float stored = RADII.get(v);
        final float base = stored != null ? stored : (g != null ? g.getRadius() : 0f);
        if (reduced(v.getContext()) || v.getVisibility() != View.VISIBLE) {
            v.setVisibility(View.GONE);
            if (end != null) end.run();
            return;
        }
        v.setPivotX(pivotX);
        v.setPivotY(pivotY);
        final float s1x = sheet ? 0.14f : 0.3f, s1y = sheet ? 0.066f : 0.2f;
        final List<View> kids = children(v);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(sheet ? 420 : 280);
        a.setInterpolator(null);
        a.addUpdateListener(an -> {
            float f = an.getAnimatedFraction();
            float e = (float) Math.pow(f, 1.7);
            v.setScaleX(1 + (s1x - 1) * e);
            v.setScaleY(1 + (s1y - 1) * Math.min(1f, e * 1.08f));
            v.setAlpha(f < 0.55f ? 1f : 1 - (f - 0.55f) / 0.45f);
            if (g != null) g.setRadius(base * (1 + 12 * e * e));
            for (View k : kids) k.setAlpha(Math.max(0f, 1 - f * 6));
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) {
                v.setVisibility(View.GONE);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setAlpha(1f);
                if (g != null) g.setRadius(base);
                for (View k : kids) {
                    k.setAlpha(1f);
                    k.setTranslationY(0f);
                }
                if (end != null) end.run();
            }
        });
        RUNNING.put(v, a);
        a.start();
    }

    private static List<View> children(View v) {
        List<View> list = new ArrayList<>();
        if (!(v instanceof ViewGroup)) return list;
        ViewGroup g = (ViewGroup) v;
        ViewGroup inner = (g.getChildCount() == 1 && g.getChildAt(0) instanceof ViewGroup)
                ? (ViewGroup) g.getChildAt(0) : g;
        for (int i = 0; i < inner.getChildCount(); i++) list.add(inner.getChildAt(i));
        return list;
    }
}
