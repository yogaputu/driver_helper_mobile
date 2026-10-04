package com.budimas.driverhelper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.io.ByteArrayOutputStream;

/** Lightweight, dependency-free receiver signature pad for delivery POD. */
final class SignaturePadView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float lastX;
    private float lastY;
    private boolean strokeMoved;
    private boolean hasInk;

    SignaturePadView(Context context) {
        super(context);
        paint.setColor(Color.rgb(17, 24, 39));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setBackgroundColor(Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawPath(path, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestParentTouchHandling(false);
                path.moveTo(x, y);
                lastX = x;
                lastY = y;
                strokeMoved = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                requestParentTouchHandling(false);
                for (int index = 0; index < event.getHistorySize(); index++) {
                    appendStroke(event.getHistoricalX(index), event.getHistoricalY(index));
                }
                appendStroke(x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                appendStroke(x, y);
                if (!strokeMoved) {
                    path.addCircle(x, y, Math.max(1f, paint.getStrokeWidth() / 2f), Path.Direction.CW);
                    hasInk = true;
                }
                requestParentTouchHandling(true);
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                requestParentTouchHandling(true);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    void clearSignature() {
        path.reset();
        hasInk = false;
        strokeMoved = false;
        invalidate();
    }

    boolean hasSignature() {
        return hasInk;
    }

    String toBase64Png() {
        if (!hasInk || getWidth() <= 0 || getHeight() <= 0) return "";
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        draw(canvas);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream);
        bitmap.recycle();
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void appendStroke(float x, float y) {
        if (Math.abs(x - lastX) < 1f && Math.abs(y - lastY) < 1f) return;
        path.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f);
        lastX = x;
        lastY = y;
        strokeMoved = true;
        hasInk = true;
    }

    private void requestParentTouchHandling(boolean allowIntercept) {
        ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!allowIntercept);
            parent = parent.getParent();
        }
    }
}
