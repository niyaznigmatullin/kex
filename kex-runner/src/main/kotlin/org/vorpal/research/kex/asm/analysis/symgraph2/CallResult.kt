package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.state.PredicateState

data class CallResult(
    val objects: Collection<GraphVertex>,
    val activeObjects: Set<GraphVertex>,
    val activeObjectsMapping: Map<GraphVertex, GraphVertex>,
    val returnValue: GraphVertex?,
    val predicateState: PredicateState,
)
