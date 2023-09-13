package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.HeapState
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.isGraphObject
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.state.term.Term

class GraphTestCase(
    val parameters: Parameters<Argument>,
    val heapState: HeapState,
    val termValues: Map<Term, Descriptor>,
    val descriptors: Parameters<Descriptor>
) {
    init {
        check(termValues.values.all { it.type is KexNull || !it.type.isGraphObject })
    }
}
