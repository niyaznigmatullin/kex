package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.asm.analysis.symgraph2.GraphObject
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term

class UnionHeapState(
    predicateState: PredicateState,
    val firstParentState: HeapState,
    val secondParentState: HeapState,
    val objMappingToSecondParent: Map<GraphObject, GraphObject>,
    val termMappingToSecondParent: Map<Term, Term>,
) : HeapState(firstParentState.objects, firstParentState.activeObjects, predicateState) {
    override fun additionalToString(stateEnumeration: Map<HeapState, Int>): String = buildString {
        append(firstParentState.additionalToString(stateEnumeration))
        append(" and ")
        append(stateEnumeration.getValue(secondParentState))
        append(" -> ")
        append(stateEnumeration.getValue(this@UnionHeapState))
    }
}
