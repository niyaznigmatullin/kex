package org.vorpal.research.kex.state.fields

import org.vorpal.research.kex.state.term.Term

open class FieldContainer(
    protected open val fields: Map<Pair<Term, String>, Term> = mutableMapOf(),
    protected open val elements: Map<Pair<Term, Int>, Term> = mutableMapOf()
) {
    fun getField(field: Pair<Term, String>) = fields.getValue(field)

    fun getElement(element: Pair<Term, Int>) = elements.getValue(element)

    fun toMutableFieldContainer() = MutableFieldContainer(fields.toMutableMap(), elements.toMutableMap())

    override fun toString(): String {
        val fieldsRepr = fields.map {
            it.key.toString() + "->" + it.value.toString()
        }.joinToString(", ")
        return "FieldContainer{fields=[${fieldsRepr}]}"
    }
}

class MutableFieldContainer(
    override val fields: MutableMap<Pair<Term, String>, Term> = mutableMapOf(),
    override val elements: MutableMap<Pair<Term, Int>, Term> = mutableMapOf()
) : FieldContainer(fields, elements) {
    fun setField(field: Pair<Term, String>, value: Term) {
        fields[field] = value
    }

    fun setElement(element: Pair<Term, Int>, value: Term) {
        elements[element] = value
    }
}