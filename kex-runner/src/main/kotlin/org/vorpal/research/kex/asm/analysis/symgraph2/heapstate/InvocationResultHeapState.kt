package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.asm.analysis.symgraph2.AbsCall
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphObject
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term

class InvocationResultHeapState(
    objects: Collection<GraphObject>,
    activeObjects: Set<GraphObject>,
    predicateState: PredicateState,
    val absCall: AbsCall,
    val parentState: HeapState,
    val termMappingToParent: Map<Term, Term>,
) : HeapState(objects, activeObjects, predicateState) {
    override fun additionalToString(stateEnumeration: Map<HeapState, Int>): String = buildString {
        append(parentState.additionalToString(stateEnumeration))
        append(" -> ")
        append(stateEnumeration.getValue(this@InvocationResultHeapState))
        append("(")
        append(absCall.method)
        append(")")
    }
}
