package org.vorpal.research.kex.symgraph

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kthelper.logging.log
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.ExperimentalTime


@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class RBTreeTest : GraphBuildingTest("graph-rbtree") {

    @Test
    @Ignore
    fun testCoverageRedBlackTree() {
        assertCoverage(cm["org/vorpal/research/kex/test/symgraph/rbtree/TreeMap"])
    }

    @Test
    @Ignore
    fun testBST() {
        println("Start testBST")
        log.debug("Start testBST")
        val builder = buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/rbtree/TreeMap"]), 4)
        println("states count = ${builder.allStates.size}")
        assert(false)
    }
}
