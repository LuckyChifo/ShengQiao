package com.shengqiao.app.ui.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.shengqiao.app.glass.R;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppBackgroundView extends View {
    private static final String TAG = "AppBackgroundView";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private Bitmap image;
    private int solidColor;
    private int generation;
    private ExecutorService executor;

    public AppBackgroundView(Context context) {
        super(context);
    }

    public AppBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AppBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void reload() {
        generation++;
        stopDecoding();
        if (image != null) {
            image.recycle();
            image = null;
        }
        solidColor = BackgroundSettings.getSolidColor(getContext());
        String uri = BackgroundSettings.getType(getContext()) == BackgroundSettings.TYPE_IMAGE
                ? BackgroundSettings.getImageUri(getContext()) : null;
        if (uri != null) {
            decodeImage(uri, generation);
        }
        invalidate();
    }

    private void decodeImage(String uri, int requestedGeneration) {
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            Bitmap decoded = null;
            try {
                decoded = decodeSampled(Uri.parse(uri));
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Unable to load custom background", error);
            }
            Bitmap result = decoded;
            post(() -> {
                if (requestedGeneration != generation) {
                    if (result != null) {
                        result.recycle();
                    }
                    return;
                }
                image = result;
                invalidate();
            });
        });
        executor.shutdown();
    }

    private Bitmap decodeSampled(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Unable to open image");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Invalid image dimensions");
        }
        int targetWidth = getWidth() > 0 ? getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int targetHeight = getHeight() > 0 ? getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        final int sampleSize = sample;
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source source = ImageDecoder.createSource(
                    getContext().getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSampleSize(sampleSize);
            });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Unable to open image");
            }
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private void stopDecoding() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(solidColor);
        if (image != null) {
            float scale = Math.max((float) getWidth() / image.getWidth(),
                    (float) getHeight() / image.getHeight());
            float left = (getWidth() - image.getWidth() * scale) / 2f;
            float top = (getHeight() - image.getHeight() * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(left, top);
            canvas.drawBitmap(image, matrix, paint);
        }
        canvas.drawColor(getContext().getColor(R.color.sq_glass_background_scrim));
    }

    @Override
    protected void onDetachedFromWindow() {
        generation++;
        stopDecoding();
        if (image != null) {
            image.recycle();
            image = null;
        }
        super.onDetachedFromWindow();
    }
}
