package th.hansub.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A glass notice at the bottom of a screen, shown in place of a system toast. It grows out of the bottom edge and shrinks back. */
final class Notice {
    private final FrameLayout host;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView view;
    private int bottomInset;
    private final Runnable hide = () -> {
        if (view != null) Motion.hide(view, view.getWidth() / 2f, view.getHeight(), false, null);
    };

    Notice(FrameLayout host) {
        this.host = host;
        this.context = host.getContext();
    }

    /** Keeps the notice above the navigation bar. */
    void setBottomInset(int px) {
        bottomInset = px;
    }

    void show(String message) {
        if (view == null) {
            view = Glass.text(context, "", 13, Glass.TEXT, true);
            view.setGravity(Gravity.CENTER);
            view.setPadding(Glass.dp(context, 18), Glass.dp(context, 12), Glass.dp(context, 18), Glass.dp(context, 12));
            view.setMaxWidth(Glass.dp(context, 520));
            view.setBackground(Glass.panel(context, 16, Glass.FILL_POP));
            view.setVisibility(View.GONE);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            p.leftMargin = Glass.dp(context, 16);
            p.rightMargin = Glass.dp(context, 16);
            host.addView(view, p);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.bottomMargin = bottomInset + Glass.dp(context, 24);
        view.setLayoutParams(lp);
        view.setText(message);
        handler.removeCallbacks(hide);
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.INVISIBLE);
            view.post(() -> {
                Motion.show(view, view.getWidth() / 2f, view.getHeight(), false, null);
                Motion.tick(view);
            });
        }
        handler.postDelayed(hide, 3400);
    }

    void cancel() {
        handler.removeCallbacksAndMessages(null);
    }
}
