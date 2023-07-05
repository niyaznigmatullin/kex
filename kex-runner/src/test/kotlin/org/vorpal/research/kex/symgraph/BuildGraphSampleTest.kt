package org.vorpal.research.kex.symgraph

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime


@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class BuildGraphSampleTest: GraphBuildingTest("build-graph") {

    @Test
    fun test() {
        val builder = buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/EmptyClass"]), 2)
        assertEquals(3, builder.allStates.size)
    }

    @Test
    fun testNode() {
        val builder = buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/Node"]), 2)
        assertEquals(9, builder.allStates.size)
    }

    @Test
    fun testWithArray() {
        val builder = buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/WithArray"]), 2)
        assertEquals(1, builder.allStates.size)
    }

    @Test
    @Ignore
    fun testCoverageNode() {
        assertCoverage(cm["org/vorpal/research/kex/test/symgraph/Node"], 1.0, 0.5)
    }
}
