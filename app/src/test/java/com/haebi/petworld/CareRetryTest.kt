package com.haebi.petworld

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareRetryTest {
    @Test fun failedQueryCanRetryWithoutResubscribing() = runBlocking {
        var attempts = 0
        val reload = flow {
            emit(0L)
            kotlinx.coroutines.delay(100)
            emit(1L)
        }
        val states = withTimeout(5_000) {
            observeRetryableCare(reload) {
                attempts++
                if (attempts == 1) throw IllegalStateException("test failure")
                flowOf(emptyList())
            }.take(4).toList()
        }
        assertEquals(listOf(
            CareFeedState.Loading,
            CareFeedState.Failed("test failure"),
            CareFeedState.Loading,
            CareFeedState.Ready(emptyList()),
        ), states)
        assertEquals(2, attempts)
    }

    @Test fun mutationResultDoesNotSwallowCancellation() {
        val cancelled = runCatching {
            resultUnlessCancelled<Unit> { throw CancellationException("cancelled") }
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertTrue(resultUnlessCancelled<Unit> { error("failed") }.isFailure)
        assertEquals(7, resultUnlessCancelled { 7 }.getOrThrow())
    }
}
