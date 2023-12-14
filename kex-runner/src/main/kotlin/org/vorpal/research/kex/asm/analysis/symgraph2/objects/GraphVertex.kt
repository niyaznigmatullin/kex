package org.vorpal.research.kex.asm.analysis.symgraph2.objects

import org.vorpal.research.kex.asm.analysis.symgraph2.GraphValue
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.state.term.Term

abstract class GraphVertex(type: KexType): GraphValue(type) {
    abstract fun remapTerms(mapping: Map<Term, Term>)
}

