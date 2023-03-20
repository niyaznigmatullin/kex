package org.vorpal.research.kex.asm.analysis.symgraph

import org.vorpal.research.kex.descriptor.Descriptor

data class HeapState(val prevHeapState: HeapState?, val absCall: AbsCall?, val objects: Collection<GraphObject>)
