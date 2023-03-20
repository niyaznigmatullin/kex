package org.vorpal.research.kex.asm.analysis.symgraph

import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.Descriptor

data class GraphObject(val descriptor: Descriptor) {
    companion object {
        val Null = GraphObject(ConstantDescriptor.Null)
    }
}
