package org.vorpal.research.kex.asm.analysis.symgraph2.objects

import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.state.term.Term

abstract class GraphVertex(val type: KexType) {

    companion object {
        val Null = object : GraphVertex(KexNull()) {
            override fun remapTerms(mapping: Map<Term, Term>) {}
        }
    }

    abstract fun remapTerms(mapping: Map<Term, Term>)
}

