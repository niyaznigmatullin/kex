package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.collections.immutable.*
import kotlinx.coroutines.yield
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.*
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.KexRtManager.isJavaRt
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.smt.AsyncChecker
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.transformer.generateReturnValue
import org.vorpal.research.kex.state.transformer.toTypeMap
import org.vorpal.research.kex.trace.symbolic.*
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.ReturnInst
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.NullType
import org.vorpal.research.kthelper.collection.queueOf

class MethodAbstractlyInvocator(
    ctx: ExecutionContext,
    rootMethod: Method,
) : SymbolicTraverser(ctx, rootMethod) {
    override val pathSelector: SymbolicPathSelector = DequePathSelector()
    override val callResolver: SymbolicCallResolver = DefaultCallResolver(ctx)
    override val invokeDynamicResolver: SymbolicInvokeDynamicResolver = DefaultCallResolver(ctx)
    private val objectsToLookAfter = mutableMapOf<Term, GraphObject>()
    private val invocationPaths = mutableListOf<CallResult>()
    private val termsOfFields = mutableMapOf<Pair<Term, String>, Term>()

    suspend fun invokeMethod(
        heapState: HeapState,
        thisArg: GraphObject,
        arguments: List<GraphObject>
    ): Collection<CallResult> {
        val firstInstruction = rootMethod.body.entry.instructions.first()
        val terms = mutableMapOf<GraphObject, Term>()
        val stateClauses = mutableListOf<StateClause>()
        val pathClauses = mutableListOf<PathClause>()
        if (thisArg == GraphObject.Null && !rootMethod.isStatic) {
            return emptyList()
        }
        val objects = heapState.objects
        objects.forEach {
            when (it) {
                GraphObject.Null -> {
                    terms[it] = const(null)
                }

//                thisArg -> {
//                    assert(!rootMethod.isStatic)
////                    kfgValues[it] = thisValue
//                    val thisTerm = `this`(rootMethod.klass.symbolicClass)
//                    terms[it] = thisTerm
//                    pathClauses.add(
//                        PathClause(
//                            PathClauseType.NULL_CHECK,
//                            firstInstruction,
//                            path { (thisTerm eq null) equality false })
//                    )
//                }

                else -> {
                    val newTerm = generate(it.type)
                    if (thisArg != it && !arguments.contains(it)) {
                        stateClauses.add(StateClause(firstInstruction, state { newTerm.new() }))
                    }
                    pathClauses.add(
                        PathClause(
                            PathClauseType.NULL_CHECK,
                            firstInstruction,
                            path { (newTerm eq null) equality false })
                    )
                    terms[it] = newTerm
                }
            }
        }
        for (obj in objects.filter { it != GraphObject.Null }) {
            val objectTerm = terms.getValue(obj)
            for ((field, fieldValue) in obj.objectFields) {
                val (fieldName, fieldType) = field
                val valueTerm = terms.getValue(fieldValue)
                val clause = StateClause(firstInstruction, state {
                    objectTerm.field(fieldType, fieldName).store(valueTerm)
                })
                stateClauses.add(clause)
            }
        }
        objectsToLookAfter.clear()
        objectsToLookAfter.putAll(terms.filter { (obj, term) -> term != const(null) && obj in heapState.activeObjects }
            .map { (obj, term) -> Pair(term, obj) })
        invocationPaths.clear()
//        println(clauses)
        val thisValue = values.getThis(rootMethod.klass)
        val initialArguments = buildMap {
            val values = this@MethodAbstractlyInvocator.values
            if (!rootMethod.isStatic) {
                val thisTerm = `this`(rootMethod.klass.symbolicClass)
                this[thisValue] = thisTerm
                stateClauses.add(StateClause(firstInstruction, state { terms.getValue(thisArg) equality thisTerm }))
//                this[thisValue] = terms.getValue(thisArg)
            }
            for ((index, type) in rootMethod.argTypes.withIndex()) {
                val argTerm = arg(type.symbolicType, index)
                this[values.getArgument(index, rootMethod, type)] = argTerm
                if (type.symbolicType is KexClass) {
                    stateClauses.add(StateClause(firstInstruction, state {
                        argTerm equality terms.getValue(arguments[index])
                    }))
                }
            }
        }
        val initialTypeInfo = terms.map { (obj, term) ->
            val kfgType = when (term.type) {
                is KexNull -> NullType
                else -> obj.type.getKfgType(types)
            }
            term to kfgType
        }.toMap().toPersistentMap()
        val initialNullCheckTerms = terms.filterValues { it.type !is KexNull }.values.toPersistentSet()
        val initialTypeCheckedTerms = initialTypeInfo
        pathSelector.add(
            TraverserState(
                symbolicState = persistentSymbolicState(
                    state = stateClauses.toPersistentClauseState(),
                    path = pathClauses.toPersistentPathCondition(),
                ),
                valueMap = initialArguments.toPersistentMap(),
                stackTrace = persistentListOf(),
                typeInfo = initialTypeInfo,
                blockPath = persistentListOf(),
                nullCheckedTerms = initialNullCheckTerms,
                boundCheckedTerms = persistentSetOf(),
                typeCheckedTerms = initialTypeCheckedTerms
            ), rootMethod.body.entry
        )

        while (pathSelector.hasNext()) {
            val (currentState, currentBlock) = pathSelector.next()
            this@MethodAbstractlyInvocator.currentState = currentState
            traverseBlock(currentBlock)
            yield()
        }

        return invocationPaths
    }

    private suspend fun checkAsyncAndGetReturn(
        method: Method, state: SymbolicState, termsToGenerate: Collection<Term>
    ): Map<Term, Descriptor> { // TODO make as checkAsync
        val checker = AsyncChecker(method, ctx)
        val clauses = state.clauses.asState()
        val query = state.path.asState()
        val concreteTypeInfo = state.concreteValueMap.mapValues { it.value.type }.filterValues { it.isJavaRt }
            .mapValues { it.value.rtMapped }.toTypeMap()
        val result = checker.prepareAndCheck(method, clauses + query, concreteTypeInfo)
        check(result is Result.SatResult)
        termsToGenerate.forEach { term ->
            val kfgType = term.type.getKfgType(types)
            for (field in (kfgType as ClassType).klass.fields) {
                val newTerm = generate(field.type.symbolicType)
                val loadTerm = term.field(field.type.symbolicType, field.name).load()
            }
        }
        return generateReturnValue(method, ctx, result.model, checker.state, termsToGenerate)
    }

    override suspend fun traverseReturnInst(inst: ReturnInst) {
        val traverserState = currentState ?: return
        val stackTrace = traverserState.stackTrace
        val stackTraceElement = stackTrace.lastOrNull()
        val receiver = stackTraceElement?.instruction
        if (receiver == null) {
//            println("Return Instruction for method: $rootMethod")
            val result = check(rootMethod, traverserState.symbolicState)
            if (result != null) {
                val returnTerm = when {
                    inst.hasReturnValue -> traverserState.mkTerm(inst.returnValue)
                    else -> null
                }
//                println("objectsToLookAfter = $objectsToLookAfter")
                val objectDescriptors = checkAsyncAndGetReturn(rootMethod,
                    traverserState.symbolicState,
                    persistentSetOf<Term>().builder().let { set ->
                        set.addAll(objectsToLookAfter.keys)
                        returnTerm?.let {
                            if (it.type is KexClass) {
                                set.add(it)
                            }
                        }
                        set.build()
                    })
                report_(inst, result, objectDescriptors, returnTerm?.let { objectDescriptors[it] })
            }
            currentState = null
        } else {
            super.traverseReturnInst(inst)
        }
    }

    private fun report_(
        inst: Instruction,
        parameters: Parameters<Descriptor>,
        objectDescriptors: Map<Term, Descriptor>,
        returnDescriptor: Descriptor?
    ) {
//        println("{inst} = $inst {parameters} = $parameters {objectDescriptors} = $objectDescriptors")
        val allObjects = mutableSetOf<Descriptor>().also {
            it.addAll(objectDescriptors.values)
        }
        val queue = queueOf(objectDescriptors.values)
        while (queue.isNotEmpty()) {
            val obj = queue.poll()!!
            if (obj == ConstantDescriptor.Null) {
                continue
            }
            for ((field, descriptor) in (obj as ObjectDescriptor).fields) {
                if (field.second !is KexClass) {
                    continue
                }
                if (!allObjects.contains(descriptor)) {
                    allObjects.add(descriptor)
                    queue.add(descriptor)
                }
            }
        }
        val mapping = allObjects.associateWith {
            when (it) {
                ConstantDescriptor.Null -> GraphObject.Null
                else -> GraphObject(it.type)
            }
        }
        mapping.filter { it.key != ConstantDescriptor.Null }.forEach { (descriptor, obj) ->
            val objectFields = buildMap {
                for ((field, descriptorTo) in (descriptor as ObjectDescriptor).fields) {
                    if (field.second is KexClass || field.second is KexNull) {
                        put(field, mapping.getValue(descriptorTo))
                    }
                }
            }
            obj.objectFields = objectFields
        }
        val activeObjects = buildSet {
            addAll(objectDescriptors.values.map { mapping.getValue(it) })
            returnDescriptor?.let { add(mapping.getValue(it)) }
        }
        invocationPaths.add(
            CallResult(
                mapping.values,
                activeObjects,
                returnDescriptor?.let { mapping.getValue(it) }
            )
        )
    }
}
