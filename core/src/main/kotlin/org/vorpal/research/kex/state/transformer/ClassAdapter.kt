package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.state.predicate.CallPredicate
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.predicate
import org.vorpal.research.kex.state.term.CallTerm
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.classClass
import org.vorpal.research.kfg.objectClass

class ClassAdapter(val cm: ClassManager) : Transformer<ClassAdapter> {
    private val getClassMethod = cm.objectClass.getMethod("getClass", cm.classClass.asType)

    override fun transformCallPredicate(predicate: CallPredicate): Predicate {
        val call = predicate.call as CallTerm
        if (!predicate.hasLhv) return predicate

        val lhv = predicate.lhv
        val `this` = call.owner

        return when (call.method) {
            getClassMethod -> predicate(predicate.type) { lhv equality `this`.klass }
            else -> predicate
        }
    }
}
