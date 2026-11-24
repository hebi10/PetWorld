package com.haebi.petworld

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.onEach
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
        val reload = Channel<Long>(Channel.UNLIMITED)
        reload.trySend(0L)
        val states = withTimeout(5_000) {
            observeRetryableCare(reload.receiveAsFlow()) {
                attempts++
                if (attempts == 1) throw IllegalStateException("test failure")
                flowOf(CareFeedState.Ready(emptyList(), null, emptyMap()))
            }.onEach { if (it is CareFeedState.Failed) reload.trySend(1L) }
                .take(4).toList()
        }
        reload.close()
        assertEquals(listOf(
            CareFeedState.Loading,
            CareFeedState.Failed("test failure"),
            CareFeedState.Loading,
            CareFeedState.Ready(emptyList(), null, emptyMap()),
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
