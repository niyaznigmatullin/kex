package org.vorpal.research.kex.symgraph

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlin.test.Ignore
import kotlin.test.Test

@ExperimentalSerializationApi
@InternalSerializationApi
class ConcurrentLinkedQueueTest: GraphBuildingTest("graph-concurrent-queue") {
    @Test
    @Ignore
    fun testConcurrentQueue() {
        buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/ConcurrentLinkedQueueFactory"]), 2)
    }
}
