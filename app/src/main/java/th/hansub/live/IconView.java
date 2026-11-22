package th.hansub.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Small line icons drawn with paths on a 24-unit grid, so the app needs no image files.
 * Strokes are round and a little heavier than hairline, in the style of Lumora's icons.
 */
final class IconView extends View {
    static final int MIC = 0, WINDOW = 1, BELL = 2, CHECK = 3, PLAY = 4, STOP = 5, TRANSLATE = 6,
            SHIELD = 7, CHEVRON = 8, CHIP = 9, UPDATE = 10;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int kind;
    private int tint = Glass.ACCENT;
    private float weight = 2f;

    IconView(Context c, int kind) {
        super(c);
        this.kind = kind;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setKind(int kind) { this.kind = kind; invalidate(); }
    void setTint(int tint) { this.tint = tint; invalidate(); }
    void setWeight(float weight) { this.weight = weight; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        if (s <= 0f) return;
        canvas.save();
        canvas.translate((getWidth() - 24f * s) / 2f, (getHeight() - 24f * s) / 2f);
        canvas.scale(s, s);
        paint.setColor(tint);
        paint.setStrokeWidth(weight);
        path.reset();
        boolean filled = false;
        switch (kind) {
            case MIC:
                path.addRoundRect(new RectF(9f, 3f, 15f, 13f), 3f, 3f, Path.Direction.CW);
                path.moveTo(18f, 11f);
                path.arcTo(new RectF(6f, 5f, 18f, 17f), 0f, 180f);
                path.moveTo(12f, 17f);
                path.lineTo(12f, 21f);
                path.moveTo(9f, 21f);
                path.lineTo(15f, 21f);
                break;
            case WINDOW:
                path.addRoundRect(new RectF(3f, 5f, 21f, 19f), 3f, 3f, Path.Direction.CW);
                path.addRoundRect(new RectF(11f, 12f, 18f, 16f), 1.5f, 1.5f, Path.Direction.CW);
                break;
            case BELL:
                path.moveTo(5f, 17f);
                path.lineTo(19f, 17f);
                path.lineTo(17.5f, 15f);
                path.lineTo(17.5f, 10.5f);
                path.cubicTo(17.5f, 7.5f, 15f, 5.5f, 12f, 5.5f);
                path.cubicTo(9f, 5.5f, 6.5f, 7.5f, 6.5f, 10.5f);
                path.lineTo(6.5f, 15f);
                path.close();
                path.moveTo(10f, 19.5f);
                path.quadTo(12f, 22f, 14f, 19.5f);
                break;
            case CHECK:
                path.moveTo(5f, 12.5f);
                path.lineTo(10f, 17.5f);
                path.lineTo(19f, 7f);
                break;
            case PLAY:
                path.moveTo(8f, 5f);
                path.lineTo(19f, 12f);
                path.lineTo(8f, 19f);
                path.close();
                filled = true;
                break;
            case STOP:
                path.addRoundRect(new RectF(6f, 6f, 18f, 18f), 2.5f, 2.5f, Path.Direction.CW);
                filled = true;
                break;
            case SHIELD:
                path.moveTo(12f, 3f);
                path.lineTo(19f, 6f);
                path.lineTo(19f, 11.5f);
                path.cubicTo(19f, 16f, 16f, 19.5f, 12f, 21f);
                path.cubicTo(8f, 19.5f, 5f, 16f, 5f, 11.5f);
                path.lineTo(5f, 6f);
                path.close();
                break;
            case CHEVRON:
                path.moveTo(9f, 6f);
                path.lineTo(15f, 12f);
                path.lineTo(9f, 18f);
                break;
            case CHIP:
                path.addRoundRect(new RectF(6f, 6f, 18f, 18f), 2.5f, 2.5f, Path.Direction.CW);
                for (float p : new float[]{9.5f, 14.5f}) {
                    path.moveTo(p, 3f);
                    path.lineTo(p, 6f);
                    path.moveTo(p, 18f);
                    path.lineTo(p, 21f);
                    path.moveTo(3f, p);
                    path.lineTo(6f, p);
                    path.moveTo(18f, p);
                    path.lineTo(21f, p);
                }
                break;
            case UPDATE:
                path.moveTo(12f, 4f);
                path.lineTo(12f, 15f);
                path.moveTo(7.5f, 10.5f);
                path.lineTo(12f, 15f);
                path.lineTo(16.5f, 10.5f);
                path.moveTo(5f, 19.5f);
                path.lineTo(19f, 19.5f);
                break;
            default:
                break;
        }
        if (kind == TRANSLATE) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(17f);
            canvas.drawText("文", 12f, 18f, paint);
        } else {
            paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
            canvas.drawPath(path, paint);
        }
        canvas.restore();
    }
}
