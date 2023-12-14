package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.descriptor.convertToDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.state.IncrementalPredicateState
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.emptyState
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.FieldLoadTerm
import org.vorpal.research.kex.state.term.FieldTerm
import org.vorpal.research.kex.util.loadClass
import org.vorpal.research.kthelper.assert.ktassert


class StaticFieldCreator(val context: ExecutionContext): Transformer<StaticFieldCreator>, IncrementalTransformer {
    override fun apply(ps: PredicateState): PredicateState {
        val staticFieldLoadTerms = collectTerms(ps) { it.type is KexClass && it is FieldLoadTerm && it.isStatic }.filterIsInstance<FieldLoadTerm>()
        val builder = emptyState().builder()
        for (fieldLoadTerm in staticFieldLoadTerms) {
            val field = fieldLoadTerm.field as FieldTerm
            val owner = field.owner
            ktassert(owner.type is KexClass)
//            val staticClass = term { staticRef(owner.type as KexClass) }
            val klass = context.loader.loadClass(context.types, owner.type)
            val fieldDescriptor = convertToDescriptor(klass.getField(field.fieldName).get(null))
            builder += fieldDescriptor.query
            builder += state { owner.field(fieldLoadTerm.type, field.name).store(fieldDescriptor.term) }
        }
        builder += ps
        return builder.apply()
    }

    override fun apply(state: IncrementalPredicateState): IncrementalPredicateState {
        return IncrementalPredicateState(apply(state.state), state.queries)
    }
}