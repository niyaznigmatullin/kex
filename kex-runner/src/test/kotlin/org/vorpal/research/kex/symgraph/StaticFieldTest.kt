package org.vorpal.research.kex.symgraph

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kfg.ir.ConcreteClass
import kotlin.test.Test
import kotlin.time.ExperimentalTime


@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class StaticFieldTest: GraphBuildingTest("graph-static-field") {
    @Test
    fun testPersistentList() {
        val klass = cm["org/vorpal/research/kex/test/symgraph/PersistentLinkedList"]
        klass as ConcreteClass
        val g = buildGraph(setOf(klass), 2)
        println(g.allStates.size)
    }
}
