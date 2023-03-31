package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.state.term.Term

class GraphObject(val type: KexType) {

    //    val primitiveFields: Map<Pair<String, KexType>, Term>,
    var objectFields = emptyMap<Pair<String, KexType>, GraphObject>()

    companion object {
        val Null = GraphObject(KexNull())
    }
}
