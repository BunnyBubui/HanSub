package th.hansub.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.view.View;

/**
 * The HanSub mark drawn as lines: a speech bubble, the two subtitle lines inside it and a spark.
 * {@link #setProgress} 0..1 draws the strokes one after another; near the end the spark fills in and the mark glows.
 * The same shapes make the launcher icon (res/drawable/ic_launcher_foreground.xml).
 */
final class LogoView extends View {
    private float progress = 1f;
    private final Path[] strokes = new Path[3];
    private final Path[] scaled = new Path[3];
    private final Path partial = new Path();
    /** When each stroke starts and ends, as shares of progress. */
    private static final float[][] WINDOWS = {{0f, 0.55f}, {0.35f, 0.82f}, {0.62f, 0.92f}};
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    LogoView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);

        // A 108-unit grid, the same as the launcher icon.
        Path bubble = new Path();
        bubble.moveTo(38f, 36f);
        bubble.lineTo(70f, 36f);
        bubble.quadTo(78f, 36f, 78f, 44f);
        bubble.lineTo(78f, 60f);
        bubble.quadTo(78f, 68f, 70f, 68f);
        bubble.lineTo(50f, 68f);
        bubble.lineTo(38f, 78f);
        bubble.lineTo(38f, 68f);
        bubble.quadTo(30f, 68f, 30f, 60f);
        bubble.lineTo(30f, 44f);
        bubble.quadTo(30f, 36f, 38f, 36f);
        bubble.close();
        Path lines = new Path();
        lines.moveTo(39f, 47f);
        lines.lineTo(69f, 47f);
        lines.moveTo(39f, 57f);
        lines.lineTo(59f, 57f);
        Path spark = new Path();
        spark.moveTo(77f, 23f);
        spark.quadTo(77.8f, 28.2f, 83f, 29f);
        spark.quadTo(77.8f, 29.8f, 77f, 35f);
        spark.quadTo(76.2f, 29.8f, 71f, 29f);
        spark.quadTo(76.2f, 28.2f, 77f, 23f);
        spark.close();
        strokes[0] = bubble;
        strokes[1] = lines;
        strokes[2] = spark;
        for (int i = 0; i < 3; i++) scaled[i] = new Path();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = Math.min(w, h) / 108f;
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - 108f * s) / 2f, (h - 108f * s) / 2f);
        for (int i = 0; i < 3; i++) {
            scaled[i].reset();
            strokes[i].transform(m, scaled[i]);
        }
        line.setStrokeWidth(3.4f * s);
        glow.setStrokeWidth(9f * s);
    }

    @Override protected void onDraw(Canvas canvas) {
        float end = Math.max(0f, Math.min(1f, (progress - 0.88f) / 0.12f));
        if (end > 0f) {
            glow.setColor(Glass.ACCENT);
            glow.setAlpha((int) (60 * end));
            for (int i = 0; i < 2; i++) canvas.drawPath(scaled[i], glow);
        }
        for (int i = 0; i < 2; i++) {
            float[] win = WINDOWS[i];
            float t = Math.max(0f, Math.min(1f, (progress - win[0]) / (win[1] - win[0])));
            if (t <= 0f) continue;
            line.setColor(i == 0 ? Glass.TEXT : Glass.ACCENT);
            drawPartial(canvas, scaled[i], ease(t));
        }
        // The spark draws as an outline, then fills.
        float[] win = WINDOWS[2];
        float t = Math.max(0f, Math.min(1f, (progress - win[0]) / (win[1] - win[0])));
        if (t > 0f) {
            line.setColor(Glass.ACCENT);
            drawPartial(canvas, scaled[2], ease(t));
        }
        if (end > 0f) {
            fill.setColor(Glass.ACCENT);
            fill.setAlpha((int) (235 * end));
            canvas.drawPath(scaled[2], fill);
        }
    }

    /** Draws the first {@code share} of the path's length, across all its contours. */
    private void drawPartial(Canvas canvas, Path path, float share) {
        PathMeasure measure = new PathMeasure(path, false);
        float total = 0f;
        do {
            total += measure.getLength();
        } while (measure.nextContour());
        float want = total * share;
        partial.reset();
        PathMeasure again = new PathMeasure(path, false);
        do {
            float len = again.getLength();
            if (want > 0f) again.getSegment(0f, Math.min(len, want), partial, true);
            want -= len;
        } while (again.nextContour());
        canvas.drawPath(partial, line);
    }

    private static float ease(float t) {
        float k = 1f - t;
        return 1f - k * k * k;
    }
}
