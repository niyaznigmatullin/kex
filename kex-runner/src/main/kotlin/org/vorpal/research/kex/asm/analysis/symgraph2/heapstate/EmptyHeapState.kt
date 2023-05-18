package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.reanimator.actionsequence.PrimaryValue
import org.vorpal.research.kex.state.emptyState
import org.vorpal.research.kex.state.term.Term

object EmptyHeapState : HeapState(
    listOf(GraphVertex.Null),
    setOf(GraphVertex.Null),
    emptyState(),
    emptySet()
) {
    override fun additionalToString(stateEnumeration: Map<HeapState, Int>) = "Empty"
    override suspend fun restoreCalls(ctx: ExecutionContext, termValues: Map<Term, Descriptor>): RestorationResult {
        return RestorationResult(mapOf(GraphVertex.Null to PrimaryValue(null)), listOf())
    }
}
