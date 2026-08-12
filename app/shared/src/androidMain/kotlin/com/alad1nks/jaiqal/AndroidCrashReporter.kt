package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.core.diagnostics.CrashReporter
import com.alad1nks.jaiqal.core.diagnostics.DeduplicatingCrashReporter
import com.alad1nks.jaiqal.core.diagnostics.NoOpCrashReporter
import com.alad1nks.jaiqal.core.diagnostics.NonFatalIssue
import com.google.firebase.crashlytics.FirebaseCrashlytics

internal fun createAndroidCrashReporter(
    firebaseConfigured: Boolean,
): CrashReporter {
    if (!firebaseConfigured) return NoOpCrashReporter
    return runCatching {
        val crashlytics = FirebaseCrashlytics.getInstance()
        DeduplicatingCrashReporter(AndroidCrashReporter(crashlytics))
    }.getOrDefault(NoOpCrashReporter)
}

private class AndroidCrashReporter(
    private val crashlytics: FirebaseCrashlytics,
) : CrashReporter {
    override suspend fun recordNonFatal(issue: NonFatalIssue) {
        crashlytics.recordException(SafeNonFatalException(issue.name))
    }
}

private class SafeNonFatalException(code: String) : RuntimeException(code)
