package org.vorpal.research.kex.asm.analysis.symgraph

import info.leadinglight.jdot.Graph
import kotlinx.collections.immutable.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.apache.commons.lang3.ObjectUtils.Null
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
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.NullTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.transformer.generateReturnValue
import org.vorpal.research.kex.state.transformer.toTypeMap
import org.vorpal.research.kex.trace.symbolic.*
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.EmptyUsageContext
import org.vorpal.research.kfg.ir.value.NullConstant
import org.vorpal.research.kfg.ir.value.Value
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
    private val objectsToLookAfter = mutableSetOf<Term>()
    private val invocationPaths = mutableListOf<CallResult>()

    suspend fun invokeMethod(
        objects: Collection<GraphObject>,
        thisArg: GraphObject,
        arguments: List<GraphObject>
    ): Collection<CallResult> {
        val kfgValues = mutableMapOf<GraphObject, Value>()
        val terms = mutableMapOf<GraphObject, Term>()
        val clauses = mutableListOf<StateClause>()
        val thisValue = values.getThis(rootMethod.klass)
        objects.forEach {
            when (it) {
                GraphObject.Null -> {
                    kfgValues[it] = NullConstant(NullType)
                    terms[it] = const(null)
                }

                thisArg -> {
                    assert(!rootMethod.isStatic)
                    kfgValues[it] = thisValue
                    terms[it] = `this`(rootMethod.klass.symbolicClass)
                }

                else -> {
                    val newInst = cm.instruction.getNew(EmptyUsageContext, it.descriptor.type.getKfgType(cm.type))
                    val newTerm = generate(it.descriptor.type)
                    clauses.add(StateClause(newInst, state { newTerm.new() }))
                    kfgValues[it] = newInst
                    terms[it] = newTerm
                }
            }
        }
        for (obj in objects) {
            if (obj == GraphObject.Null) {
                continue
            }
            for ((field, fieldDescriptor) in (obj.descriptor as ObjectDescriptor).fields) {
                val (fieldName, fieldType) = field
                if (fieldType is KexClass) {
                    println("objects = $objects")
                    println("fieldDescriptor = $fieldDescriptor, fieldName = $fieldName, fieldType = $fieldType")
                    val found = objects.find { it.descriptor == fieldDescriptor }!!
                    val kfgClassType = obj.descriptor.type.getKfgType(cm.type) as ClassType
                    val kfgField = kfgClassType.klass.getField(fieldName, fieldType.getKfgType(cm.type))
                    val fieldStoreInst = cm.instruction.getFieldStore(
                        EmptyUsageContext,
                        kfgValues.getValue(obj),
                        kfgField,
                        kfgValues.getValue(found),
                    )
                    val objectTerm = terms.getValue(obj)
                    val valueTerm = terms.getValue(found)
                    val clause = StateClause(fieldStoreInst,
                        state { objectTerm.field(obj.descriptor.type, fieldName).store(valueTerm) })
                    clauses.add(clause)
                }
            }
        }
        objectsToLookAfter.clear()
        objectsToLookAfter.addAll(persistentSetOf<Term>().builder().let {
            it.addAll(terms.values)
            it.remove(const(null))
            it.build()
        })
        invocationPaths.clear()
        println(clauses)
        val initialArguments = buildMap {
            val values = this@MethodAbstractlyInvocator.values
            if (!rootMethod.isStatic) {
                this[thisValue] = terms.getValue(thisArg)
            }
            for ((index, type) in rootMethod.argTypes.withIndex()) {
                this[values.getArgument(index, rootMethod, type)] = arg(type.symbolicType, index)
            }
        }
        val initialTypeInfo = terms.map { (obj, term) ->
            val kfgType = when (term.type) {
                is KexNull -> NullType
                else -> (obj.descriptor as ObjectDescriptor).type.getKfgType(types)
            }
            term to kfgType
        }.toMap().toPersistentMap()
        val initialNullCheckTerms = terms.filterValues { it.type !is KexNull }.values.toPersistentSet()
        val initialTypeCheckedTerms = initialTypeInfo
        pathSelector.add(
            TraverserState(
                symbolicState = persistentSymbolicState(state = PersistentClauseState(clauses.toPersistentList())),
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
        return generateReturnValue(method, ctx, result.model, checker.state, termsToGenerate)
    }

//    suspend fun invokeMethod(method: Method) {
//        val thisValue = values.getThis(method.klass)
//        val initialArguments = buildMap {
//            val values = this@SymbolicTraverser.values
//            if (!method.isStatic) {
//                this[thisValue] = `this`(method.klass.symbolicClass)
//            }
//            for ((index, type) in method.argTypes.withIndex()) {
//                this[values.getArgument(index, method, type)] = arg(type.symbolicType, index)
//            }
//        }
//        val (initialTypeInfo, initialNullCheckTerms, initialTypeCheckedTerms) = when {
//            !method.isStatic -> {
//                val thisTerm = initialArguments[thisValue]!!
//                val thisType = method.klass.symbolicClass.getKfgType(types)
//                Triple(
//                    persistentMapOf(thisTerm to thisType),
//                    persistentSetOf(thisTerm),
//                    persistentMapOf(thisTerm to thisType)
//                )
//            }
//
//            else -> Triple(persistentMapOf(), persistentSetOf(), persistentMapOf())
//        }
//        pathSelector.add(
//            TraverserState(
//                symbolicState = persistentSymbolicState(),
//                valueMap = initialArguments.toPersistentMap(),
//                stackTrace = persistentListOf(),
//                typeInfo = initialTypeInfo,
//                blockPath = persistentListOf(),
//                nullCheckedTerms = initialNullCheckTerms,
//                boundCheckedTerms = persistentSetOf(),
//                typeCheckedTerms = initialTypeCheckedTerms
//            ),
//            method.body.entry
//        )
//
//        while (pathSelector.hasNext()) {
//            val (currentState, currentBlock) = pathSelector.next()
//            this.currentState = currentState
//            traverseBlock(currentBlock)
//            yield()
//        }
//    }

    override suspend fun traverseReturnInst(inst: ReturnInst) {
        val traverserState = currentState ?: return
        val stackTrace = traverserState.stackTrace
        val stackTraceElement = stackTrace.lastOrNull()
        val receiver = stackTraceElement?.instruction
        if (receiver == null) {
            val result = check(rootMethod, traverserState.symbolicState)
            if (result != null) {
                val returnTerm = when {
                    inst.hasReturnValue -> traverserState.mkTerm(inst.returnValue)
                    else -> null
                }
                println("objectsToLookAfter = $objectsToLookAfter")
                val objectDescriptors = checkAsyncAndGetReturn(rootMethod,
                    traverserState.symbolicState,
                    persistentSetOf<Term>().builder().let { set ->
                        set.addAll(objectsToLookAfter)
                        returnTerm?.let {
                            if (it.type is KexClass) {
                                set.add(it)
                            }
                        }
                        set.build()
                    })
                report_(inst, result, objectDescriptors.values, returnTerm?.let { objectDescriptors[it] })
            }
            currentState = null
        } else {
            super.traverseReturnInst(inst)
        }
    }

    private fun report_(
        inst: Instruction,
        parameters: Parameters<Descriptor>,
        objectDescriptors: Collection<Descriptor>,
        returnDescriptor: Descriptor?
    ) {
        println("{inst} = $inst {parameters} = $parameters {objectDescriptors} = $objectDescriptors")
        val allObjects = mutableSetOf<Descriptor>().also {
            it.addAll(objectDescriptors)
        }
        val queue = queueOf(objectDescriptors)
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
        invocationPaths.add(CallResult(allObjects.map { GraphObject(it) }, returnDescriptor?.let { GraphObject(it) }))
    }
}
