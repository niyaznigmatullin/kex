package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.HeapState
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.state.term.Term

class GraphTestCase(
    val parameters: Parameters<Argument>,
    val heapState: HeapState,
    val termValues: Map<Term, ConstantDescriptor>,
    val descriptors: Parameters<Descriptor>
)
