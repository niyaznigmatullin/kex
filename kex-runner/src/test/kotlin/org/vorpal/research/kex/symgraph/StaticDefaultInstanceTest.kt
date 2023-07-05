package org.vorpal.research.kex.symgraph

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.ExperimentalTime


@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class StaticDefaultInstanceTest: GraphBuildingTest("graph-static-default-instance") {
    @Test
    @Ignore
    fun testStaticDefaultInstance() {
        buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/StaticDefaultInstance"]), 2)
    }
}
