package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.asm.analysis.symgraph2.GraphObject
import org.vorpal.research.kex.state.emptyState

object EmptyHeapState : HeapState(
    listOf(GraphObject.Null),
    setOf(GraphObject.Null),
    emptyState()
) {
    override fun additionalToString(stateEnumeration: Map<HeapState, Int>) = "Empty"
}
