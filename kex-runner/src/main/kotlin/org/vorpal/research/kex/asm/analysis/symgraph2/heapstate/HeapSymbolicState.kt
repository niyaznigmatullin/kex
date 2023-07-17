package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term

class HeapSymbolicState(
    val heapState: HeapState,
    val predicateState: PredicateState,
    val objectMapping: Map<GraphVertex, Term>
)
