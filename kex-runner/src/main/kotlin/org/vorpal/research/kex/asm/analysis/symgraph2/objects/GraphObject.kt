package org.vorpal.research.kex.asm.analysis.symgraph2.objects

import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.state.term.Term

class GraphObject(type: KexClass) : GraphVertex(type) {
    var objectFields = emptyMap<Pair<String, KexType>, GraphVertex>()
    var primitiveFields = emptyMap<Pair<String, KexType>, Term>()

    override fun toString(): String {
        val reference = super.hashCode().toString(16)
        return "GraphObject#$reference(type=$type, primitiveFields=$primitiveFields)"
    }

    override fun remapTerms(mapping: Map<Term, Term>) {
        primitiveFields = primitiveFields.mapValues { (_, value) ->
            mapping.getOrDefault(value, value)
        }
    }
}