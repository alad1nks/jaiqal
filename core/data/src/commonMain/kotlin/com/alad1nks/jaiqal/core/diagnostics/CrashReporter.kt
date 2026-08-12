package com.alad1nks.jaiqal.core.diagnostics

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Safe, bounded issue codes. Values must never contain credentials or personal data. */
enum class NonFatalIssue {
    BACKEND_SESSION_SYNC,
}

fun interface CrashReporter {
    suspend fun recordNonFatal(issue: NonFatalIssue)
}

object NoOpCrashReporter : CrashReporter {
    override suspend fun recordNonFatal(issue: NonFatalIssue) = Unit
}

/** Prevents a recurring failure from creating an unbounded stream of duplicate reports. */
class DeduplicatingCrashReporter(
    private val delegate: CrashReporter,
) : CrashReporter {
    private val mutex = Mutex()
    private val reported = mutableSetOf<NonFatalIssue>()

    override suspend fun recordNonFatal(issue: NonFatalIssue) {
        val firstOccurrence = mutex.withLock { reported.add(issue) }
        if (firstOccurrence) delegate.recordNonFatal(issue)
    }
}
