package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.ktype.isGraphObject
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.fields.FieldContainer
import org.vorpal.research.kex.state.predicate.FieldInitializerPredicate
import org.vorpal.research.kex.state.predicate.FieldStorePredicate
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.term.FieldLoadTerm
import org.vorpal.research.kex.state.term.FieldTerm
import org.vorpal.research.kex.state.term.Term

class ExpressionExtractor(initialFields: FieldContainer, val mapToRepresenter: Map<Term, Term>) :
    Transformer<ExpressionExtractor> {
    val fields = initialFields.toMutableFieldContainer()

    private fun fieldIndex(field: Term): Pair<Term, String> {
        field as FieldTerm
        return mapToRepresenter.getValue(field.owner) to field.fieldName
    }

    override fun transformFieldLoad(term: FieldLoadTerm): Term {
        val result = super.transformFieldLoad(term) as FieldLoadTerm
        if (result.field.subTerms.size == 1 && !result.field.subTerms.first().type.isGraphObject) {
            return result
        }
//        println(fields)
        return fields.getField(fieldIndex(result.field))
    }

    override fun transformFieldStore(predicate: FieldStorePredicate): Predicate {
        val result = super.transformFieldStore(predicate) as FieldStorePredicate
        if (result.field.subTerms.size == 1 && !result.field.subTerms.first().type.isGraphObject) {
            return result
        }
        fields.setField(fieldIndex(result.field), result.value)
        return nothing()
    }

    override fun transformFieldInitializer(predicate: FieldInitializerPredicate): Predicate {
        val result = super.transformFieldInitializer(predicate) as FieldInitializerPredicate
        if (result.field.subTerms.size == 1 && !result.field.subTerms.first().type.isGraphObject) {
            return result
        }
        fields.setField(fieldIndex(result.field), result.value)
        return nothing()
    }
}

fun extractValues(
    fields: FieldContainer,
    mapToRepresenter: Map<Term, Term>,
    ps: PredicateState
): Pair<FieldContainer, PredicateState> {
    val extractor = ExpressionExtractor(fields, mapToRepresenter)
    val predicateState = extractor.apply(ps)
    return extractor.fields to predicateState
}
