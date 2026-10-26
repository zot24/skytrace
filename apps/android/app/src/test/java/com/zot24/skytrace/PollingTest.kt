package com.zot24.skytrace

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun fetchesOnlyWhileStartedAndAtOnceOnReturn() = runTest(dispatcher) {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val fetchedAt = ArrayList<Long>()
        val job = launch { pollWhileStarted(owner.lifecycle) { fetchedAt += testScheduler.currentTime; 5_000L } }

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceTimeBy(12_000L); runCurrent()
        assertEquals(listOf(0L, 5_000L, 10_000L), fetchedAt)

        owner.registry.currentState = Lifecycle.State.CREATED
        advanceTimeBy(600_000L); runCurrent()
        assertEquals(3, fetchedAt.size)

        owner.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(612_000L, fetchedAt.last())
        advanceTimeBy(5_000L); runCurrent()
        assertEquals(617_000L, fetchedAt.last())
        job.cancel()
    }

    @Test
    fun aSlowLoopIsCancelledMidWaitAndRestartsOnReturn() = runTest(dispatcher) {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val refreshedAt = ArrayList<Long>()
        val job = launch {
            pollWhileStarted(owner.lifecycle) { kotlinx.coroutines.delay(5_000L); refreshedAt += testScheduler.currentTime; 115_000L }
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        advanceTimeBy(130_000L); runCurrent()
        assertEquals(listOf(5_000L, 125_000L), refreshedAt)

        owner.registry.currentState = Lifecycle.State.CREATED
        advanceTimeBy(1_000_000L); runCurrent()
        assertEquals(2, refreshedAt.size)

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceTimeBy(5_000L); runCurrent()
        assertEquals(1_135_000L, refreshedAt.last())
        job.cancel()
    }
}
