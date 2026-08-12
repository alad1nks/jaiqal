package com.alad1nks.jaiqal.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CrashReporterTest {
    @Test
    fun reportsEachSafeIssueAtMostOnce() = runTest {
        val recorded = mutableListOf<NonFatalIssue>()
        val reporter = DeduplicatingCrashReporter(recorded::add)

        reporter.recordNonFatal(NonFatalIssue.BACKEND_SESSION_SYNC)
        reporter.recordNonFatal(NonFatalIssue.BACKEND_SESSION_SYNC)

        assertEquals(listOf(NonFatalIssue.BACKEND_SESSION_SYNC), recorded)
    }
}
