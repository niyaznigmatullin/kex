package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.state.PredicateState

data class CallResult(
    val objects: Collection<GraphObject>,
    val activeObjects: Set<GraphObject>,
    val returnValue: GraphObject?,
    val predicateState: PredicateState,
)
