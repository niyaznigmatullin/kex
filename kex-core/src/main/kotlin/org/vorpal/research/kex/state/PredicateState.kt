@file:Suppress("unused")

package org.vorpal.research.kex.state

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import org.vorpal.research.kex.BaseType
import org.vorpal.research.kex.InheritanceInfo
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.PredicateBuilder
import org.vorpal.research.kex.state.predicate.PredicateType
import org.vorpal.research.kfg.ir.Location
import org.vorpal.research.kthelper.assert.fail
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log

interface InheritanceTypeInfo {
    val inheritors: Map<String, Class<*>>
    val reverseMapping: Map<Class<*>, String>
}

fun emptyState(): PredicateState = BasicState()
fun Predicate.wrap() = emptyState() + this

fun chain(base: PredicateState, curr: PredicateState): PredicateState = when {
    base.isEmpty -> curr
    curr.isEmpty -> base
    base is BasicState && curr is BasicState -> BasicState(base.predicates + curr.predicates)
    else -> ChainState(base, curr)
}

open class StateBuilder() : PredicateBuilder {
    override val type = PredicateType.State()
    override val location = Location()
    var current: PredicateState = emptyState()

    constructor(state: PredicateState) : this() {
        this.current = state
    }

    fun append(state: PredicateState): StateBuilder {
        this += state
        return this
    }

    operator fun plus(predicate: Predicate): StateBuilder {
        return StateBuilder(current + predicate)
    }

    operator fun plusAssign(predicate: Predicate) {
        current += predicate
    }

    operator fun plus(state: PredicateState) = when {
        state.isEmpty -> this
        current.isEmpty -> StateBuilder(state)
        else -> StateBuilder(chain(current, state))
    }

    operator fun plusAssign(state: PredicateState) {
        current = chain(current, state)
    }

    operator fun plus(choices: List<PredicateState>) = when {
        choices.isEmpty() -> this
        current.isEmpty -> StateBuilder(ChoiceState(choices))
        else -> StateBuilder(chain(current, ChoiceState(choices)))
    }

    operator fun plusAssign(choices: List<PredicateState>) {
        val choice = ChoiceState(choices)
        current = chain(current, choice)
    }

    open fun apply() = current

    inline fun assume(body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Assume().body()
    }
    inline fun assume(location: Location, body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Assume(location).body()
    }

    inline fun axiom(body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Axiom().body()
    }
    inline fun axiom(location: Location, body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Axiom(location).body()
    }

    inline fun state(body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.State().body()
    }
    inline fun state(location: Location, body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.State(location).body()
    }

    inline fun path(body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Path().body()
    }
    inline fun path(location: Location, body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Path(location).body()
    }

    inline fun require(body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Require().body()
    }
    inline fun require(location: Location, body: PredicateBuilder.() -> Predicate) {
        this += PredicateBuilder.Require(location).body()
    }

    inline fun predicate(type: PredicateType, body: PredicateBuilder.() -> Predicate) = when (type) {
        is PredicateType.Assume -> assume(body)
        is PredicateType.Axiom -> axiom(body)
        is PredicateType.Require -> require(body)
        is PredicateType.State -> state(body)
        is PredicateType.Path -> path(body)
        else -> fail { log.error("Unknown predicate type $type") }
    }

    inline fun predicate(type: PredicateType, location: Location, body: PredicateBuilder.() -> Predicate) = when (type) {
        is PredicateType.Assume -> assume(location, body)
        is PredicateType.Axiom -> axiom(location, body)
        is PredicateType.Require -> require(location, body)
        is PredicateType.State -> state(location, body)
        is PredicateType.Path -> path(location, body)
        else -> fail { log.error("Unknown predicate type $type") }
    }
}

inline fun basic(body: StateBuilder.() -> Unit): PredicateState {
    val sb = StateBuilder()
    sb.body()
    return sb.apply()
}

inline fun PredicateState.basic(body: StateBuilder.() -> Unit): PredicateState {
    val sb = StateBuilder()
    sb.body()
    return this + sb.apply()
}

inline fun (StateBuilder.() -> Unit).chain(curr: StateBuilder.() -> Unit): PredicateState {
    val sb = StateBuilder().apply { this@chain() }
    sb += StateBuilder().apply { curr() }.apply()
    return sb.apply()
}

inline fun PredicateState.chain(curr: StateBuilder.() -> Unit): PredicateState {
    val sb = StateBuilder(this)
    sb += StateBuilder().apply { curr() }.apply()
    return sb.apply()
}

inline fun (StateBuilder.() -> Unit).choice(right: StateBuilder.() -> Unit): PredicateState {
    val lhv = StateBuilder().apply { this@choice() }.apply()
    val rhv = StateBuilder().apply { right() }.apply()
    return StateBuilder().apply { this += listOf(lhv, rhv) }.apply()
}

class ChoiceBuilder : StateBuilder() {
    private val choices = mutableListOf<PredicateState>()

    fun or(branch: () -> PredicateState) {
        choices += branch()
    }

    override fun apply() = ChoiceState(choices)
}

inline fun PredicateState.choice(right: ChoiceBuilder.() -> Unit): PredicateState {
    val rhv = ChoiceBuilder().apply { right() }.apply()
    return this + rhv
}

@Suppress("MemberVisibilityCanBePrivate")
@BaseType("State")
@Serializable
abstract class PredicateState : InheritanceTypeInfo {
    companion object {
        val states = run {
            val loader = Thread.currentThread().contextClassLoader
            val resource = loader.getResourceAsStream("PredicateState.json")
                    ?: unreachable { log.error("No info about PS inheritors") }
            val inheritanceInfo = InheritanceInfo.fromJson(resource.bufferedReader().readText())
            resource.close()

            inheritanceInfo.inheritors.associate {
                it.name to loader.loadClass(it.inheritorClass)
            }
        }

        val reverse = states.map { it.value to it.key }.toMap()
    }

    abstract val size: Int

    val isEmpty: Boolean
        get() = size == 0

    val isNotEmpty: Boolean
        get() = !isEmpty

    val path by lazy { filterByType(PredicateType.Path()) }

    override val inheritors get() = states
    override val reverseMapping get() = reverse

    abstract fun print(): String

    override fun toString() = print()

    open fun map(transform: (Predicate) -> Predicate): PredicateState = fmap { it.map(transform) }
    open fun mapNotNull(transform: (Predicate) -> Predicate?): PredicateState = fmap { it.mapNotNull(transform) }
    open fun filter(predicate: (Predicate) -> Boolean): PredicateState = fmap { it.filter(predicate) }
    fun all(predicate: (Predicate) -> Boolean): Boolean = size == filter(predicate).size
    fun any(predicate: (Predicate) -> Boolean): Boolean = filter(predicate).size > 0
    fun filterNot(predicate: (Predicate) -> Boolean) = filter { !predicate(it) }
    fun filterByType(type: PredicateType) = filter { it.type == type }

    fun take(n: Int): PredicateState {
        var counter = 0
        return this.filter { counter++ < n  }
    }

    fun takeLast(n: Int): PredicateState = reverse().take(n).reverse()

    fun drop(n: Int): PredicateState {
        var counter = 0
        return this.filter { ++counter > n }
    }

    fun dropLast(n: Int): PredicateState = reverse().drop(n).reverse()

    fun takeWhile(predicate: (Predicate) -> Boolean): PredicateState {
        var found = false
        return filter {
            when {
                found -> false
                predicate(it) -> {
                    found = true
                    false
                }
                else -> true
            }
        }
    }

    fun takeLastWhile(predicate: (Predicate) -> Boolean): PredicateState = reverse().takeWhile(predicate).reverse()

    fun dropWhile(predicate: (Predicate) -> Boolean): PredicateState {
        var found = false
        return filter {
            when {
                found -> true
                predicate(it) -> {
                    found = true
                    true
                }
                else -> false
            }
        }
    }

    fun dropLastWhile(predicate: (Predicate) -> Boolean): PredicateState = reverse().dropWhile(predicate).reverse()

    fun startsWith(ps: PredicateState): Boolean = sliceOn(ps) != null

    abstract fun fmap(transform: (PredicateState) -> PredicateState): PredicateState
    abstract fun reverse(): PredicateState

    abstract fun addPredicate(predicate: Predicate): PredicateState
    operator fun plus(predicate: Predicate) = addPredicate(predicate)

    abstract fun sliceOn(state: PredicateState): PredicateState?

    abstract fun simplify(): PredicateState

    fun builder() = StateBuilder(this)
    operator fun plus(state: PredicateState) = (builder() + state).apply()
    operator fun plus(states: List<PredicateState>) = (builder() + states).apply()
}


data class PredicateQuery(
    val hardConstraints: PredicateState,
    val softConstraints: PersistentList<Predicate> = persistentListOf()
)

data class IncrementalPredicateState(
    val state: PredicateState,
    val queries: List<PredicateQuery>
)
