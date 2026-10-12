package com.neurolens.eyecare;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Test APK only. Uses platform Java so its separate process needs no target APK runtime. */
public class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        View view = new View(this) {
            private final Paint paint = new Paint();
            @Override protected void onDraw(Canvas canvas) {
                boolean alternate = (SystemClock.elapsedRealtime() / 700) % 2 == 0;
                canvas.drawColor(alternate ? Color.rgb(60, 90, 120) : Color.rgb(220, 190, 155));
                paint.setColor(Color.WHITE);
                paint.setTextSize(30f);
                canvas.drawText("EyeCare capture test", 24f, 120f, paint);
                postInvalidateDelayed(700);
            }
        };
        view.setContentDescription("EyeCare color fixture");
        setContentView(view);
    }
}
