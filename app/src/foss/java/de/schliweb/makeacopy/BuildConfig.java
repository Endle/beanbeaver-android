/*
 * Copyright 2026 BeanBeaver contributors
 *
 * Licensed under the MIT licence (see LICENSE). This file is *not* from
 * MakeACopy; it exists so that MakeACopy's files can be, unchanged.
 */
package de.schliweb.makeacopy;

/**
 * A stand-in for MakeACopy's generated {@code BuildConfig}, so the vendored
 * DocQuad sources under {@code ml/} compile here untouched.
 *
 * <p>Those files are copied verbatim from MakeACopy (Apache-2.0) and reference
 * two constants from their own app's generated BuildConfig. Repointing those
 * imports at {@code com.zhenbo.beanbeaver.BuildConfig} would have been two
 * edits, but it would also mean the vendored files no longer diff clean against
 * upstream — and a clean diff is the whole point of copying rather than
 * rewriting them. Supplying the constants they expect keeps
 * {@code ml/**} byte-identical to the release it came from, so re-syncing is a
 * plain {@code cp} and any local change shows up immediately in review.
 *
 * <p>AGP generates the real {@code com.zhenbo.beanbeaver.BuildConfig}; this one
 * is hand-written and only ever holds what the vendored code reads.
 */
public final class BuildConfig {

    /** Mirrors the app's own build type, so vendored debug logging follows it. */
    public static final boolean DEBUG = com.zhenbo.beanbeaver.BuildConfig.DEBUG;

    /**
     * Verbose corner-detection logging. MakeACopy gates it on a build flag of
     * its own; here it follows DEBUG, which keeps it out of release builds where
     * it would log receipt geometry on every frame.
     */
    public static final boolean FEATURE_FRAMING_LOGGING = com.zhenbo.beanbeaver.BuildConfig.DEBUG;

    private BuildConfig() {}
}
