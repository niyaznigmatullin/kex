package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.state.term.Term

class GraphObject(val type: KexType) {
    var objectFields = emptyMap<Pair<String, KexType>, GraphObject>()
    var primitiveFields = emptyMap<Pair<String, KexType>, Term>()

    companion object {
        val Null = GraphObject(KexNull())
    }

    fun remapTerms(mapping: Map<Term, Term>) {
        primitiveFields = primitiveFields.mapValues { (_, value) ->
            mapping.getValue(value)
        }
    }

    override fun toString(): String {
        return "GraphObject(type=$type, primitiveFields=$primitiveFields)"
    }


}
