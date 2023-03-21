package org.vorpal.research.kex.asm.analysis.symgraph

data class CallResult(val objects: Collection<GraphObject>, val activeObjects: Set<GraphObject>, val returnValue: GraphObject?)
