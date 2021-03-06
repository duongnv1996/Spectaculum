/*
 * Copyright 2014 Mario Guggenberger <mg@protyposis.net>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.protyposis.android.spectaculum;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;

import net.protyposis.android.spectaculum.effects.Effect;
import net.protyposis.android.spectaculum.effects.EffectException;
import net.protyposis.android.spectaculum.effects.Parameter;
import net.protyposis.android.spectaculum.effects.ParameterHandler;
import net.protyposis.android.spectaculum.gles.*;

/**
 * Created by Mario on 14.06.2014.
 */
public class SpectaculumView extends GLSurfaceView implements
        SurfaceTexture.OnFrameAvailableListener,
        Effect.Listener, GLRenderer.EffectEventListener,
        GLRenderer.OnFrameCapturedCallback {

    private static final String TAG = SpectaculumView.class.getSimpleName();

    public interface EffectEventListener extends GLRenderer.EffectEventListener {}
    public interface OnFrameCapturedCallback extends GLRenderer.OnFrameCapturedCallback {}

    private GLRenderer mRenderer;
    private InputSurfaceHolder mInputSurfaceHolder;
    private Handler mRunOnUiThreadHandler = new Handler();
    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector mGestureDetector;

    private EffectEventListener mEffectEventListener;
    private OnFrameCapturedCallback mOnFrameCapturedCallback;

    private PipelineResolution mPipelineResolution = PipelineResolution.SOURCE;

    private float mZoomLevel = 1.0f;
    private float mZoomSnappingRange = 0.02f;
    private float mPanX;
    private float mPanY;
    private float mPanSnappingRange = 0.02f;
    private boolean mTouchEnabled = false;

    protected int mImageWidth;
    protected int mImageHeight;

    public SpectaculumView(Context context) {
        super(context);
        init(context);
    }

    public SpectaculumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        if(isInEditMode()) {
            // do not start renderer in layout editor
            return;
        }
        if(!net.protyposis.android.spectaculum.gles.GLUtils.isGlEs2Supported(context)) {
            Log.e(TAG, "GLES 2.0 is not supported");
            return;
        }

        LibraryHelper.setContext(context);

        mRenderer = new GLRenderer();
        mRenderer.setOnExternalSurfaceTextureCreatedListener(mExternalSurfaceTextureCreatedListener);
        mRenderer.setEffectEventListener(mRendererEffectEventListener);

        mInputSurfaceHolder = new InputSurfaceHolder();

        setEGLContextClientVersion(2);
        setRenderer(mRenderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        // TODO setPreserveEGLContextOnPause(true);

        mScaleGestureDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        mZoomLevel *= detector.getScaleFactor();

                        if(LibraryHelper.isBetween(mZoomLevel, 1-mZoomSnappingRange, 1+mZoomSnappingRange)) {
                            mZoomLevel = 1.0f;
                        }

                        // limit zooming to magnification zooms (zoom-ins)
                        if(mZoomLevel < 1.0f) {
                            mZoomLevel = 1.0f;
                        }

                        setZoom(mZoomLevel);
                        return true;
                    }
                });

        mGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                        // divide by zoom level to adjust panning speed to zoomed picture size
                        // multiply by fixed scaling factor to compensate for panning lag
                        mPanX += distanceX / getWidth() / mZoomLevel * 1.2f;
                        mPanY += distanceY / getHeight() / mZoomLevel * 1.2f;

                        float panSnappingRange = mPanSnappingRange / mZoomLevel;
                        if(LibraryHelper.isBetween(mPanX, -panSnappingRange, +panSnappingRange)) {
                            mPanX = 0;
                        }
                        if(LibraryHelper.isBetween(mPanY, -panSnappingRange, +panSnappingRange)) {
                            mPanY = 0;
                        }

                        // limit panning to the texture bounds so it always covers the complete view
                        float maxPanX = Math.abs((1.0f / mZoomLevel) - 1.0f);
                        float maxPanY = Math.abs((1.0f / mZoomLevel) - 1.0f);
                        mPanX = LibraryHelper.clamp(mPanX, -maxPanX, maxPanX);
                        mPanY = LibraryHelper.clamp(mPanY, -maxPanY, maxPanY);

                        setPan(mPanX, mPanY);
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        mZoomLevel = 1;
                        mPanX = 0;
                        mPanY = 0;

                        setZoom(mZoomLevel);
                        setPan(mPanX, mPanY);

                        return true;
                    }
                });
    }

    /**
     * Sets the zoom factor of the texture in the view. 1.0 means no zoom, 2.0 2x zoom, etc.
     */
    public void setZoom(float zoomFactor) {
        mZoomLevel = zoomFactor;
        mRenderer.setZoomLevel(mZoomLevel);
        requestRender(GLRenderer.RenderRequest.GEOMETRY);
    }

    /**
     * Gets the zoom level.
     * @see #setZ(float) for an explanation if the value
     * @return
     */
    public float getZoomLevel() {
        return mZoomLevel;
    }

    /**
     * Sets the panning of the texture in the view. (0.0, 0.0) centers the texture and means no
     * panning, (-1.0, -1.0) moves the texture to the lower right quarter.
     */
    public void setPan(float x, float y) {
        mPanX = x;
        mPanY = y;
        mRenderer.setPan(-mPanX, mPanY);
        requestRender(GLRenderer.RenderRequest.GEOMETRY);
    }

    /**
     * Gets the horizontal panning. Zero means centered, positive is to the left.
     */
    public float getPanX() {
        return mPanX;
    }

    /**
     * Gets the vertical panning. Zero means centered, positive is to the bottom.
     */
    public float getPanY() {
        return mPanY;
    }

    /**
     * Enables or disables touch zoom/pan gestures. When disabled, a parent container (e.g. an activity)
     * can still pass touch events to this view's {@link #onTouchEvent(MotionEvent)} to process
     * zoom/pan gestures.
     * @see #isTouchEnabled()
     */
    public void setTouchEnabled(boolean enabled) {
        mTouchEnabled = enabled;
    }

    /**
     * Checks if touch gestures are enabled. Touch gestures are disabled by default.
     * @see #setTouchEnabled(boolean)
     */
    public boolean isTouchEnabled() {
        return mTouchEnabled;
    }

    /**
     * Resizes the video view according to the video size to keep aspect ratio.
     * Code copied from {@link android.widget.VideoView#onMeasure(int, int)}.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Log.i("@@@@", "onMeasure(" + MeasureSpec.toString(widthMeasureSpec) + ", "
                + MeasureSpec.toString(heightMeasureSpec) + ")");

        int width = getDefaultSize(mImageWidth, widthMeasureSpec);
        int height = getDefaultSize(mImageHeight, heightMeasureSpec);
        if (mImageWidth > 0 && mImageHeight > 0) {

            int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
            int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

            if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
                // the size is fixed
                width = widthSpecSize;
                height = heightSpecSize;

                // for compatibility, we adjust size based on aspect ratio
                if ( mImageWidth * height  < width * mImageHeight) {
                    //Log.i("@@@", "image too wide, correcting");
                    width = height * mImageWidth / mImageHeight;
                } else if ( mImageWidth * height  > width * mImageHeight) {
                    //Log.i("@@@", "image too tall, correcting");
                    height = width * mImageHeight / mImageWidth;
                }
            } else if (widthSpecMode == MeasureSpec.EXACTLY) {
                // only the width is fixed, adjust the height to match aspect ratio if possible
                width = widthSpecSize;
                height = width * mImageHeight / mImageWidth;
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    // couldn't match aspect ratio within the constraints
                    height = heightSpecSize;
                }
            } else if (heightSpecMode == MeasureSpec.EXACTLY) {
                // only the height is fixed, adjust the width to match aspect ratio if possible
                height = heightSpecSize;
                width = height * mImageWidth / mImageHeight;
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    // couldn't match aspect ratio within the constraints
                    width = widthSpecSize;
                }
            } else {
                // neither the width nor the height are fixed, try to use actual video size
                width = mImageWidth;
                height = mImageHeight;
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    // too tall, decrease both width and height
                    height = heightSpecSize;
                    width = height * mImageWidth / mImageHeight;
                }
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    // too wide, decrease both width and height
                    width = widthSpecSize;
                    height = width * mImageHeight / mImageWidth;
                }
            }
        } else {
            // no size yet, just adopt the given spec sizes
        }
        setMeasuredDimension(width, height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        /*
         * NOTE: These calls should not be simplified to a logical chain, because the evaluation
         * would stop at the first true value and not execute the following functions.
         */
        boolean event1 = mScaleGestureDetector.onTouchEvent(event);
        boolean event2 = mGestureDetector.onTouchEvent(event);
        return event1 || event2;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if(!mTouchEnabled) {
            // Touch events are disabled and we return false to route all events to the parent
            return false;
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Implement this method to receive the input surface holder when it is ready to be used.
     * The input surface holder holds the surface and surface texture to which input data, i.e. image
     * data from some source that should be processed and displayed, should be written to display
     * it in the view.
     *
     * External callers should add a callback to the holder through {@link InputSurfaceHolder#addCallback(InputSurfaceHolder.Callback)}
     * to be notified about this event in {@link InputSurfaceHolder.Callback#surfaceCreated(InputSurfaceHolder)}.
     *
     * @param inputSurfaceHolder the input surface holder which holds the surface where image data should be written to
     */
    public void onInputSurfaceCreated(InputSurfaceHolder inputSurfaceHolder) {
        // nothing to do here
    }

    /**
     * Gets the input surface holder that holds the surface where image data should be written to
     * for processing and display. The holder is always available but only holds an actual surface
     * after {@link #onInputSurfaceCreated(InputSurfaceHolder)} respectively
     * {@link InputSurfaceHolder.Callback#surfaceCreated(InputSurfaceHolder)} have been called.
     *
     * The input surface holder holds the input surface (texture) that is used to write image data
     * into the processing pipeline, opposed to the surface holder from {@link #getHolder()} that holds
     * the surface to which the final result of the processing pipeline will be written to for display.
     *
     * @return the input surface holder or null if it is not available yet
     */
    public InputSurfaceHolder getInputHolder() {
        return mInputSurfaceHolder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Delete the external texture, else it stays in RAM
        if(getInputHolder().getExternalSurfaceTexture() != null) {
            getInputHolder().getExternalSurfaceTexture().delete();
            getInputHolder().update(null);
        }
        super.surfaceDestroyed(holder);
    }

    /**
     * Adds one or more effects to the view. Added effects can then be activated/selected by calling
     * {@link #selectEffect(int)}. The effect indices start at zero and are in the order that they
     * are added to the view.
     * @param effects effects to add
     */
    public void addEffect(final Effect... effects) {
        for(Effect effect : effects) {
            effect.setListener(this);
            effect.setParameterHandler(new ParameterHandler(this));
        }
        queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.addEffect(effects);
            }
        });
    }

    /**
     * Selects/activates the effect with the given index as it has been added through {@link #addEffect(Effect...)}.
     * @param index the index of the effect to activate
     */
    public void selectEffect(final int index) {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.selectEffect(index);
                requestRender(GLRenderer.RenderRequest.EFFECT);
            }
        });
    }

    /**
     * Gets called when an effect has been initialized after being selected for the first time
     * with {@link #selectEffect(int)}. Effect initialization happens asynchronously and can take
     * some time when a lot of data (framebuffers, textures, ...) is loaded.
     * Can be overwritten in subclasses but must be called through. External callers should use
     * {@link #setEffectEventListener(EffectEventListener)}.
     * @param index the index of the initialized effect
     * @param effect the initialized effect
     */
    @Override
    public void onEffectInitialized(int index, Effect effect) {
        if(mEffectEventListener != null) {
            mEffectEventListener.onEffectInitialized(index, effect);
        }
        requestRender(GLRenderer.RenderRequest.EFFECT);
    }

    /**
     * Gets called when an effect has been successfully selected with {@link #selectEffect(int)}.
     * Can be overwritten in subclasses but must be called through. External callers should use
     * {@link #setEffectEventListener(EffectEventListener)}.
     * @param index the index of the selected effect
     * @param effect the selected effect
     */
    @Override
    public void onEffectSelected(int index, Effect effect) {
        if(mEffectEventListener != null) {
            mEffectEventListener.onEffectSelected(index, effect);
        }
    }

    /**
     * Gets called when an effect selection with {@link #selectEffect(int)} fails.
     * Can be overwritten in subclasses but must be called through. External callers should use
     * {@link #setEffectEventListener(EffectEventListener)}.
     * @param index the index of the failed effect
     * @param effect the failed effect
     */
    @Override
    public void onEffectError(int index, Effect effect, EffectException e) {
        Log.e(TAG, "effect error", e);
        if(mEffectEventListener != null) {
            mEffectEventListener.onEffectError(index, effect, e);
        }
    }

    /**
     * Sets an event listener that gets called when effect-related event happens.
     * @param listener the event listener to be called on an event
     */
    public void setEffectEventListener(EffectEventListener listener) {
        mEffectEventListener = listener;
    }

    /**
     * Gets called when a parameter of an effect has changed. This method then triggers a fresh
     * rendering of the effect. Can be overridden in subclasses but must be called through.
     * @param effect the effect of which a parameter value has changed
     * @see net.protyposis.android.spectaculum.effects.Effect.Listener
     */
    @Override
    public void onEffectChanged(Effect effect) {
        requestRender(GLRenderer.RenderRequest.EFFECT);
    }

    /**
     * Gets called when a parameter is added to an effect.
     * Can be overridden in subclasses but must be called through.
     * @param effect the effect to which a parameter was added
     * @param parameter the added parameter
     * @see net.protyposis.android.spectaculum.effects.Effect.Listener
     */
    @Override
    public void onParameterAdded(Effect effect, Parameter parameter) {
        // nothing to do here
    }

    /**
     * Gets called when a parameter is removed from an effect.
     * Can be overridden in subclasses but must be called through.
     * @param effect the effect from which a parameter was removed
     * @param parameter the removed parameter
     * @see net.protyposis.android.spectaculum.effects.Effect.Listener
     */
    @Override
    public void onParameterRemoved(Effect effect, Parameter parameter) {
        // nothing to do here
    }

    /**
     * Gets called when a new image frame has been written to the surface texture and requests a
     * fresh rendering of the view. The texture can be obtained through {@link #onInputSurfaceCreated(InputSurfaceHolder)}
     * or {@link #getInputHolder()}.
     * Can be overridden in subclasses but must be called through.
     * @param surfaceTexture the updated surface texture
     */
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        requestRender(GLRenderer.RenderRequest.ALL);
    }

    /**
     * Requests a render pass of the specified render pipeline section.
     * @param renderRequest specifies the pipeline section to be rendered
     */
    protected void requestRender(final GLRenderer.RenderRequest renderRequest) {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setRenderRequest(renderRequest);
                requestRender();
            }
        });
    }

    /**
     * Requests a capture of the current frame on the view. The frame is asynchronously requested
     * from the renderer and will be passed back on the UI thread to {@link #onFrameCaptured(Bitmap)}
     * and the event listener that can be set with {@link #setOnFrameCapturedCallback(OnFrameCapturedCallback)}.
     */
    public void captureFrame() {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.saveCurrentFrame(new GLRenderer.OnFrameCapturedCallback() {
                    @Override
                    public void onFrameCaptured(final Bitmap bitmap) {
                        mRunOnUiThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                SpectaculumView.this.onFrameCaptured(bitmap);
                            }
                        });
                    }
                });
            }
        });
    }

    /**
     * Receives a captured frame from the renderer. Can be overwritten in subclasses but must be
     * called through. External callers should use {@link #setOnFrameCapturedCallback(OnFrameCapturedCallback)}.
     */
    @Override
    public void onFrameCaptured(Bitmap bitmap) {
        if(mOnFrameCapturedCallback != null) {
            mOnFrameCapturedCallback.onFrameCaptured(bitmap);
        }
    }

    /**
     * Sets a callback event handler that receives a bitmap of the captured frame.
     */
    public void setOnFrameCapturedCallback(OnFrameCapturedCallback callback) {
        mOnFrameCapturedCallback = callback;
    }

    /**
     * Sets the resolution mode of the processing pipeline.
     * @see PipelineResolution
     */
    public void setPipelineResolution(PipelineResolution resolution) {
        mPipelineResolution = resolution;
    }

    /**
     * Gets the configured resolution mode of the processing pipeline.
     */
    public PipelineResolution getPipelineResolution() {
        return mPipelineResolution;
    }

    /**
     * Sets the resolution of the source data and recomputes the layout. This implicitly also sets
     * the resolution of the view output surface if pipeline resolution mode {@link PipelineResolution#SOURCE}
     * is set. In SOURCE mode, output will therefore be computed in the input resolution and then
     * at the very end scaled (most often downscaled) to fit the view in the layout.
     *
     * TODO decouple input, processing and output resolution
     *
     * @param width the width of the input image data
     * @param height the height of the input image data
     */
    public void updateResolution(int width, int height) {
        if(width == mImageWidth && height == mImageHeight) {
            // Don't do anything if resolution has stayed the same
            return;
        }

        mImageWidth = width;
        mImageHeight = height;

        // If desired, set output resolution to source resolution
        if (width != 0 && height != 0 && mPipelineResolution == PipelineResolution.SOURCE) {
            getHolder().setFixedSize(width, height);
        }

        // Resize view according to the new size to fit the layout
        requestLayout();
    }

    private GLRenderer.OnExternalSurfaceTextureCreatedListener mExternalSurfaceTextureCreatedListener =
            new GLRenderer.OnExternalSurfaceTextureCreatedListener() {
        @Override
        public void onExternalSurfaceTextureCreated(final ExternalSurfaceTexture surfaceTexture) {
            // dispatch event to UI thread
            mRunOnUiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    // Create an input surface holder and call the event handler
                    mInputSurfaceHolder.update(surfaceTexture);
                    onInputSurfaceCreated(mInputSurfaceHolder);
                }
            });

            surfaceTexture.setOnFrameAvailableListener(SpectaculumView.this);
        }
    };

    /**
     * Effect event listener that transfers the events to the UI thread.
     */
    private EffectEventListener mRendererEffectEventListener = new EffectEventListener() {
        @Override
        public void onEffectInitialized(final int index, final Effect effect) {
            mRunOnUiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    SpectaculumView.this.onEffectInitialized(index, effect);
                }
            });
        }

        @Override
        public void onEffectSelected(final int index, final Effect effect) {
            mRunOnUiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    SpectaculumView.this.onEffectSelected(index, effect);
                }
            });
        }

        @Override
        public void onEffectError(final int index, final Effect effect, final EffectException e) {
            mRunOnUiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    SpectaculumView.this.onEffectError(index, effect, e);
                }
            });
        }
    };
}
