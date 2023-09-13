package org.vorpal.research.kex.asm.analysis.symgraph2.objects

import org.vorpal.research.kex.ktype.KexArray
import org.vorpal.research.kex.ktype.KexPointer
import org.vorpal.research.kex.state.term.Term

//abstract class GraphArray(type: KexArray) : GraphVertex(type) {
//    companion object {
//        fun create(type: KexArray) = when (type.element) {
//            is KexPointer -> GraphArrayObject(type)
//            else -> GraphArrayPrimitive(type)
//        }
//    }
//}
//
//class GraphArrayPrimitive(type: KexArray) : GraphArray(type) {
//    var elements = emptyMap<Int, Term>()
//
//    init {
//        check(type.element !is KexPointer)
//    }
//
//    override fun remapTerms(mapping: Map<Term, Term>) {
//        elements = elements.mapValues { mapping.getValue(it.value) }
//    }
//}
//
//class GraphArrayObject(type: KexArray) : GraphArray(type) {
//    var elements = emptyMap<Int, GraphVertex>()
//
//    init {
//        check(type.element is KexPointer)
//    }
//
//    override fun remapTerms(mapping: Map<Term, Term>) {}
//}
