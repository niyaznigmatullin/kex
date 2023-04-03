package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term

data class CallResult(
    val objects: Collection<GraphObject>,
    val activeObjects: Set<GraphObject>,
    val returnValue: GraphObject?,
    val predicateState: PredicateState,
    val freeTerms: Collection<Term>,
)
