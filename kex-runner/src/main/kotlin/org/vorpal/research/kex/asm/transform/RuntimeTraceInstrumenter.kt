package org.vorpal.research.kex.asm.transform

import org.vorpal.research.kex.trace.`object`.TraceCollector
import org.vorpal.research.kex.trace.`object`.TraceCollectorProxy
import org.vorpal.research.kex.util.asmString
import org.vorpal.research.kex.util.wrapValue
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.EmptyUsageContext
import org.vorpal.research.kfg.ir.value.UsageContext
import org.vorpal.research.kfg.ir.value.ValueFactory
import org.vorpal.research.kfg.ir.value.instruction.BranchInst
import org.vorpal.research.kfg.ir.value.instruction.CallInst
import org.vorpal.research.kfg.ir.value.instruction.CmpInst
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.InstructionBuilder
import org.vorpal.research.kfg.ir.value.instruction.InstructionFactory
import org.vorpal.research.kfg.ir.value.instruction.JumpInst
import org.vorpal.research.kfg.ir.value.instruction.ReturnInst
import org.vorpal.research.kfg.ir.value.instruction.SwitchInst
import org.vorpal.research.kfg.ir.value.instruction.TableSwitchInst
import org.vorpal.research.kfg.ir.value.instruction.ThrowInst
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kfg.type.objectType
import org.vorpal.research.kfg.type.stringType
import org.vorpal.research.kfg.visitor.MethodVisitor

class RuntimeTraceInstrumenter(override val cm: ClassManager) : MethodVisitor, InstructionBuilder {
    override val ctx: UsageContext = EmptyUsageContext
    private val collectorClass = cm[TraceCollector::class.java.canonicalName.asmString]
    private lateinit var traceCollector: Instruction

    override val instructions: InstructionFactory
        get() = cm.instruction
    override val types: TypeFactory
        get() = cm.type
    override val values: ValueFactory
        get() = cm.value

    private val objectType = types.objectType
    private val stringType = types.stringType

    private fun getNewCollector(): Instruction {
        val proxy = cm[TraceCollectorProxy::class.java.canonicalName.asmString]
        val getter = proxy.getMethod("currentCollector", cm.type.getRefType(collectorClass))

        return getter.staticCall(proxy, "collector", listOf())
    }

    private fun List<Instruction>.insertBefore(inst: Instruction) {
        inst.parent.insertBefore(inst, *this.toTypedArray())
    }

    override fun cleanup() {}

    override fun visitBranchInst(inst: BranchInst) {
        val blockExitInsts = buildList {
            val branchMethod = collectorClass.getMethod(
                "blockBranch",
                types.voidType, stringType, objectType, objectType
            )
            val (condition, expected) = when (inst.cond) {
                is CmpInst -> {
                    val cmp = inst.cond as CmpInst
                    val lhv = when {
                        cmp.lhv.type.isPrimitive -> wrapValue(cmp.lhv).also { add(it) }
                        else -> cmp.lhv
                    }
                    val rhv = when {
                        cmp.rhv.type.isPrimitive -> wrapValue(cmp.rhv).also { add(it) }
                        else -> cmp.rhv
                    }
                    lhv to rhv
                }

                else -> {
                    val wrap = wrapValue(inst.cond).also { add(it) }
                    wrap to values.nullConstant
                }
            }
            add(
                branchMethod.interfaceCall(
                    collectorClass,
                    traceCollector,
                    listOf("${inst.parent.name}".asValue, condition, expected)
                )
            )
        }
        inst.parent.insertBefore(inst, *blockExitInsts.toTypedArray())
    }

    override fun visitJumpInst(inst: JumpInst) {
        val blockExitInsts = buildList<Instruction> {
            val jumpMethod = collectorClass.getMethod(
                "blockJump",
                types.voidType, stringType
            )
            add(
                jumpMethod.interfaceCall(
                    collectorClass,
                    traceCollector,
                    listOf("${inst.parent.name}".asValue)
                )
            )
        }
        inst.parent.insertBefore(inst, *blockExitInsts.toTypedArray())
    }

    override fun visitSwitchInst(inst: SwitchInst) = buildList {
        val switchMethod = collectorClass.getMethod(
            "blockSwitch",
            types.voidType, stringType, objectType
        )
        val key = run {
            val key = inst.key
            when {
                key.type.isPrimitive -> wrapValue(key).also { add(it) }
                else -> key
            }
        }
        add(
            switchMethod.interfaceCall(
                collectorClass,
                traceCollector,
                listOf("${inst.parent.name}".asValue, key)
            )
        )
    }.insertBefore(inst)

    override fun visitTableSwitchInst(inst: TableSwitchInst) = buildList {
        val switchMethod = collectorClass.getMethod(
            "blockSwitch",
            types.voidType, stringType, objectType
        )
        val key = run {
            val key = inst.index
            when {
                key.type.isPrimitive -> wrapValue(key).also { add(it) }
                else -> key
            }
        }
        add(
            switchMethod.interfaceCall(
                collectorClass,
                traceCollector,
                listOf("${inst.parent.name}".asValue, key)
            )
        )
    }.insertBefore(inst)

    override fun visitThrowInst(inst: ThrowInst) = when {
        inst.parent.method.isStaticInitializer -> buildList {
            val returnMethod = collectorClass.getMethod("staticExit", types.voidType)
            add(returnMethod.interfaceCall(collectorClass, traceCollector, listOf()))
        }

        else -> buildList {
            val throwMethod = collectorClass.getMethod(
                "methodThrow",
                types.voidType, stringType, types.getRefType("java/lang/Throwable")
            )
            add(
                throwMethod.interfaceCall(
                    collectorClass,
                    traceCollector,
                    listOf("${inst.parent.name}".asValue, inst.throwable)
                )
            )
        }
    }.insertBefore(inst)

    override fun visitReturnInst(inst: ReturnInst) = when {
        inst.parent.method.isStaticInitializer -> buildList {
            val returnMethod = collectorClass.getMethod("staticExit", types.voidType)
            add(returnMethod.interfaceCall(collectorClass, traceCollector, listOf()))
        }

        else -> buildList {
            val returnMethod = collectorClass.getMethod(
                "methodReturn",
                types.voidType, stringType
            )
            add(returnMethod.interfaceCall(collectorClass, traceCollector, listOf("${inst.parent.name}".asValue)))
        }
    }.insertBefore(inst)

    override fun visitCallInst(inst: CallInst) {
        buildList {
            val callMethod = collectorClass.getMethod(
                "methodCall",
                types.voidType,
                stringType, stringType, stringType.asArray,
                stringType, stringType, stringType, stringType.asArray
            )
            val sizeVal = values.getInt(inst.method.argTypes.size)
            val stringArray = stringType.newArray(sizeVal).also { add(it) }
            val argArray = stringType.newArray(sizeVal).also { add(it) }
            for ((index, arg) in inst.method.argTypes.withIndex()) {
                add(stringArray.store(index, arg.asmDesc.asValue))
                add(argArray.store(index, inst.args[index].toString().asValue))
            }

            val method = inst.method
            add(
                callMethod.interfaceCall(
                    collectorClass,
                    traceCollector,
                    listOf(
                        method.klass.fullName.asValue,
                        method.name.asValue,
                        stringArray,
                        method.returnType.asmDesc.asValue,
                        if (inst.isNameDefined) inst.toString().asValue else values.nullConstant,
                        if (inst.isStatic) values.nullConstant else inst.callee.toString().asValue,
                        argArray
                    )
                )
            )
        }.insertBefore(inst)
    }

    override fun visitBasicBlock(bb: BasicBlock) {
        super.visitBasicBlock(bb)
        buildList<Instruction> {
            val entryMethod = collectorClass.getMethod(
                "blockEnter", types.voidType, stringType
            )
            add(
                entryMethod.interfaceCall(
                    collectorClass,
                    traceCollector,
                    listOf("${bb.name}".asValue)
                )
            )
        }.insertBefore(bb.first())
    }

    override fun visit(method: Method) {
        if (!method.hasBody) return

        val methodEntryInsts = when {
            method.isStaticInitializer -> buildList {
                traceCollector = getNewCollector()
                add(traceCollector)
                val entryMethod = collectorClass.getMethod(
                    "staticEntry",
                    types.voidType, stringType
                )
                add(
                    entryMethod.interfaceCall(
                        collectorClass,
                        traceCollector,
                        listOf(method.klass.fullName.asValue)
                    )
                )
            }

            else -> buildList {
                traceCollector = getNewCollector()
                add(traceCollector)
                val entryMethod = collectorClass.getMethod(
                    "methodEnter", types.voidType,
                    stringType, stringType, stringType.asArray,
                    stringType, objectType, objectType.asArray
                )
                val sizeVal = method.argTypes.size.asValue
                val stringArray = stringType.newArray(sizeVal).also { add(it) }
                val argArray = objectType.newArray(sizeVal).also { add(it) }
                for ((index, arg) in method.argTypes.withIndex()) {
                    add(stringArray.store(index, arg.asmDesc.asValue))
                    val argValue = values.getArgument(index, method, arg).let { argValue ->
                        when {
                            arg.isPrimitive -> wrapValue(argValue).also { add(it) }
                            else -> argValue
                        }
                    }
                    add(argArray.store(index, argValue))
                }

                add(
                    entryMethod.interfaceCall(
                        collectorClass,
                        traceCollector,
                        listOf(
                            method.klass.fullName.asValue,
                            method.name.asValue,
                            stringArray,
                            method.returnType.asmDesc.asValue,
                            if (method.isStatic || method.isConstructor) values.nullConstant else values.getThis(method.klass),
                            argArray
                        )
                    )
                )
            }
        }
        super.visit(method)
        methodEntryInsts.insertBefore(method.body.entry.first())
    }
}
