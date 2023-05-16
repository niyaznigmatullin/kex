package org.vorpal.research.kex.asm.analysis.concolic.legacy

import org.vorpal.research.kex.asm.state.InvalidInstructionError
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.state.BasicState
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.StateBuilder
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.term
import org.vorpal.research.kex.state.transformer.Optimizer
import org.vorpal.research.kex.state.transformer.TermRenamer
import org.vorpal.research.kex.state.transformer.collectArguments
import org.vorpal.research.kex.state.transformer.collectTerms
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.ConcreteClass
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.Argument
import org.vorpal.research.kfg.ir.value.Constant
import org.vorpal.research.kfg.ir.value.IntConstant
import org.vorpal.research.kfg.ir.value.ThisRef
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.ArrayLoadInst
import org.vorpal.research.kfg.ir.value.instruction.ArrayStoreInst
import org.vorpal.research.kfg.ir.value.instruction.BinaryInst
import org.vorpal.research.kfg.ir.value.instruction.BranchInst
import org.vorpal.research.kfg.ir.value.instruction.CallInst
import org.vorpal.research.kfg.ir.value.instruction.CastInst
import org.vorpal.research.kfg.ir.value.instruction.CatchInst
import org.vorpal.research.kfg.ir.value.instruction.CmpInst
import org.vorpal.research.kfg.ir.value.instruction.EnterMonitorInst
import org.vorpal.research.kfg.ir.value.instruction.ExitMonitorInst
import org.vorpal.research.kfg.ir.value.instruction.FieldLoadInst
import org.vorpal.research.kfg.ir.value.instruction.FieldStoreInst
import org.vorpal.research.kfg.ir.value.instruction.InstanceOfInst
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.JumpInst
import org.vorpal.research.kfg.ir.value.instruction.NewArrayInst
import org.vorpal.research.kfg.ir.value.instruction.NewInst
import org.vorpal.research.kfg.ir.value.instruction.PhiInst
import org.vorpal.research.kfg.ir.value.instruction.ReturnInst
import org.vorpal.research.kfg.ir.value.instruction.SwitchInst
import org.vorpal.research.kfg.ir.value.instruction.TableSwitchInst
import org.vorpal.research.kfg.ir.value.instruction.TerminateInst
import org.vorpal.research.kfg.ir.value.instruction.ThrowInst
import org.vorpal.research.kfg.ir.value.instruction.UnaryInst
import org.vorpal.research.kfg.ir.value.instruction.UnreachableInst
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kthelper.collection.listOf
import org.vorpal.research.kthelper.collection.stackOf

class UnsupportedInstructionException(val inst: Instruction) : Exception(inst.print())
class InvalidStateException(msg: String) : Exception(msg)

class ConcolicStateBuilder(val cm: ClassManager, val psa: PredicateStateAnalysis) {
    private val types get() = cm.type
    private val stateBuilder = StateBuilder()
    private val callStack = stackOf<MutableMap<Value, Term>>(mutableMapOf())
    private val valueMap get() = callStack.peek()
    private val returnReceivers = stackOf<Term>()
    private val inlinedCalls = stackOf<Method>()
    private var counter = 0
    private var lastCall: Pair<Method, CallParameters>? = null

    fun apply() = Optimizer().apply(stateBuilder.apply())

    data class CallParameters(val receiver: Value?, val mappings: Map<Value, Value>)

    fun enterMethod(method: Method) {
        if (lastCall != null) {
            val params = lastCall!!.second
            // if call params are not null, we should already have value map
            params.receiver?.run { returnReceivers.push(mkNewValue(this)) }
            callStack.push(valueMap.toMutableMap())
            inlinedCalls.push(method)
            for ((arg, value) in params.mappings) {
                valueMap[arg] = mkValue(value)
            }
            lastCall = null
        } else {
            callStack.push(valueMap.toMutableMap())
        }
    }

    fun callMethod(method: Method, parameters: CallParameters) {
        lastCall = method to parameters
    }

    @Suppress("UNUSED_PARAMETER")
    fun exitMethod(method: Method) {
        callStack.pop()
    }

    fun build(block: BasicBlock, entry: BasicBlock?, exit: BasicBlock?) {
        for (inst in block) {
            build(inst, entry, exit)
        }
    }

    fun build(instruction: Instruction, entry: BasicBlock?, exit: BasicBlock?) = when (instruction) {
        is PhiInst -> buildPhiInst(instruction, entry!!)
        is TerminateInst -> buildTerminateInst(instruction, exit)
        else -> buildInst(instruction)
    }

    private fun buildInst(instruction: Instruction) = when (instruction) {
        is ArrayLoadInst -> buildArrayLoadInst(instruction)
        is ArrayStoreInst -> buildArrayStoreInst(instruction)
        is BinaryInst -> buildBinaryInst(instruction)
        is CallInst -> buildCallInst(instruction)
        is CastInst -> buildCastInst(instruction)
        is CatchInst -> buildCatchInst(instruction)
        is CmpInst -> buildCmpInst(instruction)
        is EnterMonitorInst -> buildEnterMonitorInst(instruction)
        is ExitMonitorInst -> buildExitMonitorInst(instruction)
        is FieldLoadInst -> buildFieldLoadInst(instruction)
        is FieldStoreInst -> buildFieldStoreInst(instruction)
        is InstanceOfInst -> buildInstanceOfInst(instruction)
        is NewArrayInst -> buildNewArrayInst(instruction)
        is NewInst -> buildNewInst(instruction)
        is UnaryInst -> buildUnaryInst(instruction)
        else -> throw UnsupportedInstructionException(instruction)
    }

    private fun buildTerminateInst(inst: TerminateInst, next: BasicBlock?) =
        when (inst) {
            is ReturnInst -> buildReturnInst(inst)
            is ThrowInst -> buildThrowInst(inst)
            is UnreachableInst -> buildUnreachableInst(inst)
            else -> next?.run {
                when (inst) {
                    is BranchInst -> buildBranchInst(inst, next)
                    is JumpInst -> buildJumpInst(inst, next)
                    is SwitchInst -> buildSwitchInst(inst, next)
                    is TableSwitchInst -> buildTableSwitchInst(inst, next)
                    else -> throw UnsupportedInstructionException(inst)
                }
            }
        }

    private fun newValue(value: Value) = term {
        when (value) {
            is Argument -> arg(value)
            is Constant -> const(value)
            is ThisRef -> `this`(value.type.kexType)
            else -> termFactory.getValue(value.type.kexType, "$value.${++counter}")
        }
    }

    private fun mkValue(value: Value) = valueMap.getOrPut(value) { newValue(value) }

    private fun mkNewValue(value: Value): Term {
        val v = newValue(value)
        valueMap[value] = v
        return v
    }

    private fun buildPhiInst(inst: PhiInst, entry: BasicBlock) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val rhv = mkValue(
                inst.incomings[entry]
                    ?: throw InvalidStateException("Inst ${inst.print()} predecessors does not correspond to actual predecessor ${entry.name}")
            )
            lhv equality rhv
        }
    }

    private fun buildArrayLoadInst(inst: ArrayLoadInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val ref = mkValue(inst.arrayRef)
            val index = mkValue(inst.index)
            val arrayRef = ref[index]
            val load = arrayRef.load()

            lhv equality load
        }
    }

    private fun buildArrayStoreInst(inst: ArrayStoreInst) {
        stateBuilder += state(inst.location) {
            val ref = mkValue(inst.arrayRef)
            val index = mkValue(inst.index)
            val arrayRef = ref[index]
            val value = mkValue(inst.value)

            arrayRef.store(value)
        }
    }

    private fun buildBinaryInst(inst: BinaryInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val rhv = mkValue(inst.lhv).apply(types, inst.opcode, mkValue(inst.rhv))

            lhv equality rhv
        }
    }

    private fun buildCallInst(inst: CallInst) {
        when (inst.method) {
            inlinedCalls.peek() -> inlinedCalls.pop()
            else -> {
                var fallback: PredicateState = BasicState(listOf {
                    state(inst.location) {
                        val args = inst.args.map { mkValue(it) }
                        val callee = when {
                            inst.isStatic -> staticRef(inst.method.klass)
                            else -> mkValue(inst.callee)
                        }
                        val callTerm = callee.call(inst.method, args)

                        when {
                            inst.isNameDefined -> mkNewValue(inst).call(callTerm)
                            else -> call(callTerm)
                        }
                    }
                })
                if (lastCall != null) {
                    val method = inst.method

                    fallback = resolveMethodCall(method) ?: fallback
                    lastCall = null
                    val retTerm = collectTerms(fallback) { it.name.startsWith("<retval>") }.firstOrNull()
                    if (retTerm != null) {
                        fallback += state(inst.location) {
                            mkNewValue(inst) equality retTerm
                        }
                    }
                }
                stateBuilder += fallback
            }
        }
    }

    private fun buildMappings(method: Method, state: PredicateState, callMappings: CallParameters): Map<Term, Term> {
        val (`this`, args) = collectArguments(state)
        val mappings = mutableMapOf<Term, Term>()
        if (!method.isStatic) {
            val actualThis = `this` ?: term { `this`(method.klass.kexType) }
            val mappedThis = mkValue(callMappings.mappings[cm.value.getThis(method.klass)]!!)
            mappings += actualThis to mappedThis
        }
        for ((_, argTerm) in args) {
            val valueArg = cm.value.getArgument(argTerm.index, method, argTerm.type.getKfgType(types))
            val mappedArg = mkValue(callMappings.mappings[valueArg]!!)
            mappings += argTerm to mappedArg
        }
        return mappings
    }

    private fun getOverloadedMethod(callee: Value, baseMethod: Method): Method {
        val klass = (callee.type as ClassType).klass
        return klass.getMethod(baseMethod.name, baseMethod.desc)
    }

    private fun resolveMethodCall(method: Method): PredicateState? {
        if (lastCall == null) return null
        if (method.body.isEmpty()) return null
        if (method.klass !is ConcreteClass) return null
        val (callMethod, callMappings) = lastCall!!
        return when {
            method.isStatic -> {
                val builder = psa.builder(method)
                builder.methodState
            }
            method.isConstructor -> {
                val builder = psa.builder(method)
                val state = builder.methodState ?: return null
                val mappings = buildMappings(method, state, callMappings)
                TermRenamer("${++counter}", mappings).apply(state)
            }
            else -> {
                val mappedThis = callMappings.mappings[cm.value.getThis(method.klass)]!!
                val actualMethod = getOverloadedMethod(mappedThis, callMethod)

                val builder = psa.builder(actualMethod)
                val state = builder.methodState ?: return null
                val mappings = buildMappings(method, state, callMappings)
                TermRenamer("${++counter}", mappings).apply(state)
            }
        }
    }


    private fun buildCastInst(inst: CastInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val rhv = mkValue(inst.operand) `as` inst.type.kexType

            lhv equality rhv
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildCatchInst(inst: CatchInst) {
    }

    private fun buildCmpInst(inst: CmpInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val rhv = mkValue(inst.lhv).apply(inst.opcode, mkValue(inst.rhv))

            lhv equality rhv
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildEnterMonitorInst(inst: EnterMonitorInst) {
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildExitMonitorInst(inst: ExitMonitorInst) {
    }

    private fun buildFieldLoadInst(inst: FieldLoadInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val owner = when {
                inst.isStatic -> staticRef(inst.field.klass)
                else -> mkValue(inst.owner)
            }
            val field = owner.field(inst.type.kexType, inst.field.name)
            val rhv = field.load()

            lhv equality rhv
        }
    }

    private fun buildFieldStoreInst(inst: FieldStoreInst) {
        stateBuilder += state(inst.location) {
            val owner = when {
                inst.isStatic -> staticRef(inst.field.klass)
                else -> mkValue(inst.owner)
            }
            val value = mkValue(inst.value)
            val field = owner.field(inst.field.type.kexType, inst.field.name)

            field.store(value)
        }
    }

    private fun buildInstanceOfInst(inst: InstanceOfInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val rhv = mkValue(inst.operand) `is` inst.targetType.kexType

            lhv equality rhv
        }
    }

    private fun buildNewArrayInst(inst: NewArrayInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val dimensions = inst.dimensions.map { mkValue(it) }

            lhv.new(dimensions)
        }
    }

    private fun buildNewInst(inst: NewInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            lhv.new()
        }
    }

    private fun buildUnaryInst(inst: UnaryInst) {
        stateBuilder += state(inst.location) {
            val lhv = mkNewValue(inst)
            val rhv = mkValue(inst.operand).apply(inst.opcode)

            lhv equality rhv
        }
    }

    private fun buildBranchInst(inst: BranchInst, next: BasicBlock) {
        stateBuilder += path(inst.location) {
            val cond = mkValue(inst.cond)
            when (next) {
                inst.trueSuccessor -> cond equality true
                inst.falseSuccessor -> cond equality false
                else -> throw InvalidStateException("Inst ${inst.print()} successor does not correspond to actual successor ${next.name}")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildJumpInst(inst: JumpInst, next: BasicBlock) {
    }

    private fun buildReturnInst(inst: ReturnInst) {
        stateBuilder += state(inst.location) {
            when {
                !inst.hasReturnValue -> const(true) equality true
                returnReceivers.isEmpty() -> const(true) equality true
                else -> {
                    val retval = returnReceivers.pop()
                    val value = mkValue(inst.returnValue)
                    retval equality value
                }
            }
        }
    }

    private fun buildSwitchInst(inst: SwitchInst, next: BasicBlock) {
        val key = mkValue(inst.key)

        when (next) {
            inst.default -> stateBuilder += path(inst.location) { key `!in` inst.branches.keys.map { mkValue(it) } }
            in inst.branches.values -> {
                for ((value, branch) in inst.branches) {
                    if (branch == next) {
                        stateBuilder += path(inst.location) { key equality mkValue(value) }
                    }
                }
            }
            else -> throw InvalidStateException("Inst ${inst.print()} successor does not correspond to actual successor ${next.name}")
        }
    }

    private fun buildTableSwitchInst(inst: TableSwitchInst, next: BasicBlock) {
        val key = mkValue(inst.index)
        val min = inst.min as? IntConstant ?: throw InvalidInstructionError("Unexpected min type in tableSwitchInst")
        val max = inst.max as? IntConstant ?: throw InvalidInstructionError("Unexpected max type in tableSwitchInst")

        when (next) {
            inst.default -> stateBuilder += path(inst.location) { key `!in` (min.value..max.value).map { const(it) } }
            in inst.branches -> {
                for ((index, branch) in inst.branches.withIndex()) {
                    if (branch == next) {
                        stateBuilder += path(inst.location) { key equality (min.value + index) }
                    }
                }
            }
            else -> throw InvalidStateException("Inst ${inst.print()} successor does not correspond to actual successor ${next.name}")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildThrowInst(inst: ThrowInst) {
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildUnreachableInst(inst: UnreachableInst) {
    }
}
