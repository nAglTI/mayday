package org.debs.mayday.feature.semantic

import android.util.Log

internal object SemanticDiagnostics {

    inline fun d(
        tag: String,
        message: () -> String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }

    inline fun w(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (!BuildConfig.DEBUG) return
        if (throwable == null) {
            Log.w(tag, message())
        } else {
            Log.w(tag, message(), throwable)
        }
    }
}
