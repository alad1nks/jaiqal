package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.core.diagnostics.CrashReporter
import com.alad1nks.jaiqal.core.diagnostics.DeduplicatingCrashReporter
import com.alad1nks.jaiqal.core.diagnostics.NoOpCrashReporter
import com.alad1nks.jaiqal.core.diagnostics.NonFatalIssue

interface IosCrashReporterBridge {
    fun recordNonFatal(code: String)
}

internal fun createIosCrashReporter(bridge: IosCrashReporterBridge?): CrashReporter =
    bridge?.let { DeduplicatingCrashReporter(IosCrashReporter(it)) } ?: NoOpCrashReporter

private class IosCrashReporter(
    private val bridge: IosCrashReporterBridge,
) : CrashReporter {
    override suspend fun recordNonFatal(issue: NonFatalIssue) {
        bridge.recordNonFatal(issue.name)
    }
}
