/*
 * Copyright 2026 BeanBeaver contributors
 *
 * Licensed under the MIT licence (see LICENSE). This file is *not* from
 * MakeACopy; it exists so that MakeACopy's files can be, unchanged.
 */
package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;

/**
 * The two detectors BeanBeaver uses, reachable from outside this package.
 *
 * <p>{@link ThrottledDocQuadLiveDetector} is package-private upstream, and
 * MakeACopy reaches it through {@code CornerDetectorFactory} — which we cannot
 * use, because that factory composes every detector with an OpenCV one and
 * OpenCV is deliberately absent here. Rather than widen a vendored class (which
 * would break the byte-identical rule this tree is built on), the accessor lives
 * in a file of our own that happens to sit in the same package.
 *
 * <p>Both detectors share one {@link DocQuadOrtRunner}: it holds the ONNX
 * Runtime session for the 13 MB model, and is a process-wide singleton upstream
 * for exactly that reason.
 */
public final class BbDocQuad {

    /**
     * Detector for a captured still. Runs once, on the full-resolution frame, so
     * its corners are the ones actually used to crop.
     */
    public static CornerDetector forStill(Context ctx) throws Exception {
        return new DocQuadDetector(runner(ctx));
    }

    /**
     * Detector for the live preview. Rate-limits inference and applies a
     * One-Euro filter so the on-screen quad tracks smoothly instead of twitching
     * frame to frame.
     */
    public static CornerDetector forPreview(Context ctx) throws Exception {
        return new ThrottledDocQuadLiveDetector(
                ctx.getApplicationContext(), runner(ctx), OneEuroCornerSmoother.withDefaults());
    }

    /** True once the model has been loaded, so callers can avoid a cold-start stall. */
    public static boolean isReady() {
        return DocQuadOrtRunner.isInstanceLoaded();
    }

    /** Drops the ONNX session. The capture screen owns the model's lifetime. */
    public static void release() {
        DocQuadOrtRunner.releaseInstance();
    }

    private static DocQuadOrtRunner runner(Context ctx) throws Exception {
        return DocQuadOrtRunner.getInstance(
                ctx.getApplicationContext(), DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
    }

    private BbDocQuad() {}
}
