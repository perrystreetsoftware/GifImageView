package com.felipecsl.gifimageview.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;

public class GifImageView extends ImageView implements Runnable {

  private static final String TAG = "GifDecoderView";
  private GifDecoder gifDecoder;
  private Bitmap tmpBitmap; // single, decoded frame from gifDecoder
  private Bitmap tmpBitmapFinished; // processed (by OnFrameAvailable) output frame
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean animating;
  private boolean shouldClear;
  private Thread animationThread;
  private OnFrameAvailable frameCallback = null;
  private long framesDisplayDuration = -1l;

  // stopAnimationAutomaticallyWhenHidden == true;
  // The Gif render thread will release itself as soon as this view is not on screen
  // any more (isShown() == false).
  // Note: If the view comes back into the visible area or is reused (e.g. RecyclerView)
  // startAnimation() must be called again.
  //
  // stopAnimationAutomaticallyWhenHidden == false;
  // The GIF render thread will continue animating the gif until stopAnimation() is called
  // explicitly. This is even true after the view disappeared or it's Intent is dismissed.
  // You must call stopAnimation().
  //
  public boolean stopAnimationAutomaticallyWhenHidden = true;

  private final Runnable updateResults = new Runnable() {
    @Override
    public void run() {

      if (GifImageView.this.stopAnimationAutomaticallyWhenHidden && !GifImageView.this.isShown()) {
//        if (ScruffActivity.DEBUG)
//          Log.i(TAG, "not shown - stop animating");

        GifImageView.this.stopAnimation();
      } else {
//        if (ScruffActivity.DEBUG)
//          Log.i(TAG, "shown - animating");
      }

      if (tmpBitmapFinished != null && !tmpBitmapFinished.isRecycled()) {
        // Trigger a redraw in onDraw(...)
        invalidate();
      }
    }
  };

  @Override
  protected void onDraw(Canvas canvas) {
    // ChatBubbles drawn for static and gif images must look the same.
    // Moved to using onDraw() like chatBubbleImageView to render both bubbles exactly
    // the same. (There were slight but noticeable differences in the insets of bubbles)
    super.onDraw(canvas);

    if (tmpBitmapFinished != null && !tmpBitmapFinished.isRecycled()) {
      Bitmap bitmap = tmpBitmapFinished;

      if (frameCallback == null) {
        Rect scaledRect = scaleToFit(gifDecoder.getWidth(),
                                     gifDecoder.getHeight(),
                                     getMeasuredWidth(),
                                     getMeasuredHeight());
        bitmap = Bitmap.createScaledBitmap(tmpBitmapFinished,
                                           scaledRect.width(),
                                           scaledRect.height(), false);
      }

      // center in view
      int x = (getMeasuredWidth() - bitmap.getWidth()) / 2;
      int y = (getMeasuredHeight() - bitmap.getHeight()) / 2;

      canvas.drawBitmap(bitmap, x, y, null);
    }
  }

  private Rect scaleToFit(int inWidth, int inHeight, int width, int height) {
    if (width == 0 || height == 0) {
      return null;
    }

    int outWidth;
    int outHeight;

    float bitmapRatio = (float) inWidth / (float)inHeight;
    float canvasRatio = (float)width / (float)height;

    if (bitmapRatio >= canvasRatio) {
      // fix width to canvas, scale height
      outWidth = getMeasuredWidth();
      outHeight = (int) (((float)inHeight / (float)inWidth) * (float)outWidth);
    } else {
      // fix height to canvas, scale width
      outHeight = getMeasuredHeight();
      outWidth = (int) (((float)inWidth / (float)inHeight) * (float)outHeight);
    }

    if (outWidth > 0 && outHeight > 0) {
      Rect rectScaled = new Rect(0, 0, outWidth, outHeight);

      return rectScaled;
    } else {
      return new Rect(0, 0, inWidth, inHeight);
    }
  }

  private final Runnable cleanupRunnable = new Runnable() {
    @Override
    public void run() {
      tmpBitmap = null;
      tmpBitmapFinished = null;
      gifDecoder = null;
      animationThread = null;
      shouldClear = false;
    }
  };

  public GifImageView(final Context context, final AttributeSet attrs) {
    super(context, attrs);
  }

  public GifImageView(final Context context) {
    super(context);
  }

  public void setBytes(final byte[] bytes) {
    gifDecoder = new GifDecoder();
    try {
      gifDecoder.read(bytes);
      gifDecoder.advance();
    } catch (final OutOfMemoryError e) {
      gifDecoder = null;
      Log.e(TAG, e.getMessage(), e);
      return;
    }

    if (canStart()) {
      animationThread = new Thread(this);
      animationThread.start();
    }
  }

  public long getFramesDisplayDuration() {
    return framesDisplayDuration;
  }

  /**
   * Sets custom display duration in milliseconds for the all frames. Should be called before {@link
   * #startAnimation()}
   *
   * @param framesDisplayDuration Duration in milliseconds. None value = -1, this property will
   *                              be ignored and default delay from gif file will be used.
   */
  public void setFramesDisplayDuration(long framesDisplayDuration) {
    this.framesDisplayDuration = framesDisplayDuration;
  }

  public void startAnimation() {
    animating = true;

    if (canStart()) {
      animationThread = new Thread(this);
      animationThread.start();
    }
  }

  public boolean isAnimating() {
    return animating;
  }

  public void stopAnimation() {
    animating = false;

    if (animationThread != null) {
      animationThread.interrupt();
      animationThread = null;
    }
  }

  public void clear() {
    animating = false;
    shouldClear = true;
    stopAnimation();
    handler.post(cleanupRunnable);
  }

  private boolean canStart() {
    return animating && gifDecoder != null && animationThread == null;
  }

  public int getGifWidth() {
    return gifDecoder.getWidth();
  }

  public int getGifHeight() {
    return gifDecoder.getHeight();
  }

  @Override public void run() {
    if (shouldClear) {
      handler.post(cleanupRunnable);
      return;
    }

    final int n = gifDecoder.getFrameCount();
    do {
      for (int i = 0; i < n; i++) {
        if (!animating) {
          break;
        }
        //milliseconds spent on frame decode
        long frameDecodeTime = 0;
        try {
          long before = System.nanoTime();
          tmpBitmap = gifDecoder.getNextFrame();
          frameDecodeTime = (System.nanoTime() - before) / 1000000;
          if (frameCallback != null) {
            tmpBitmap = frameCallback.onFrameAvailable(tmpBitmap);
          }

          if (!animating) {
            break;
          }

          tmpBitmapFinished = tmpBitmap;
          handler.post(updateResults);
        } catch (final ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
          Log.w(TAG, e);
        }
        if (!animating) {
          break;
        }
        gifDecoder.advance();
        try {
          int delay = gifDecoder.getNextDelay();
          // Sleep for frame duration minus time already spent on frame decode
          // Actually we need next frame decode duration here,
          // but I use previous frame time to make code more readable
          delay -= frameDecodeTime;
          if (delay > 0) {
            Thread.sleep(framesDisplayDuration > 0 ? framesDisplayDuration : delay);
          }
        } catch (final Exception e) {
          // suppress any exception
          // it can be InterruptedException or IllegalArgumentException
        }
      }
    } while (animating);
  }

  public OnFrameAvailable getOnFrameAvailable() {
    return frameCallback;
  }

  public void setOnFrameAvailable(OnFrameAvailable frameProcessor) {
    this.frameCallback = frameProcessor;
  }

  public interface OnFrameAvailable {
    Bitmap onFrameAvailable(Bitmap bitmap);
  }
}
