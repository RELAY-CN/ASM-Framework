/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.util.AsmMethodCallGenerator
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * RETURN 注入器
 * 在每个 RETURN 之前注入代码
 *
 * @author Dr (dr@der.kim)
 */
class ReturnInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    override fun inject(target: MethodNode): Boolean {
        val instructions = target.instructions
        var transformed = false
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0

        // 检查是否需要 CallbackInfo
        val needsCallbackInfo = AsmMethodCallGenerator.needsCallbackInfo(asmMethod)

        // 查找所有 RETURN 指令
        val insns = instructions.toArray()
        val returnType = Type.getReturnType(target.desc)

        for (insn in insns) {
            if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                val il = InsnList()

                // 在 RETURN 之前，返回值已经在栈顶（如果是非 void）
                // 先保存返回值到局部变量
                var returnVarIndex: Int? = null
                if (returnType != Type.VOID_TYPE) {
                    returnVarIndex = allocateLocalVariable(target, returnType, null)
                    when (returnType.sort) {
                        Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                            il.add(VarInsnNode(Opcodes.ISTORE, returnVarIndex))
                        }
                        Type.LONG -> {
                            il.add(VarInsnNode(Opcodes.LSTORE, returnVarIndex))
                        }
                        Type.FLOAT -> {
                            il.add(VarInsnNode(Opcodes.FSTORE, returnVarIndex))
                        }
                        Type.DOUBLE -> {
                            il.add(VarInsnNode(Opcodes.DSTORE, returnVarIndex))
                        }
                        else -> {
                            il.add(VarInsnNode(Opcodes.ASTORE, returnVarIndex))
                        }
                    }
                }

                // 创建 CallbackInfo 实例（如果需要）
                var callbackVarIndex: Int? = null
                if (needsCallbackInfo) {
                    AsmMethodCallGenerator.generateCallbackInfoCreation(il)
                    // 存储到局部变量（确保在 returnVarIndex 之后）
                    callbackVarIndex =
                        if (returnVarIndex != null) {
                            val returnVarSize = if (returnType.sort == Type.LONG || returnType.sort == Type.DOUBLE) 2 else 1
                            returnVarIndex + returnVarSize
                        } else {
                            allocateLocalVariable(target, Type.getType(CallbackInfo::class.java), null)
                        }
                    il.add(VarInsnNode(Opcodes.ASTORE, callbackVarIndex))

                    // 如果返回值不是 void，设置到 CallbackInfo
                    if (returnType != Type.VOID_TYPE && returnVarIndex != null) {
                        // 先加载 CallbackInfo 到栈
                        il.add(VarInsnNode(Opcodes.ALOAD, callbackVarIndex))
                        // 加载返回值（从 returnVarIndex）
                        InstructionUtil.loadParam(returnType, returnVarIndex).let { il.add(it) }
                        // 装箱（如果需要）
                        if (returnType.sort in
                            setOf(Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR, Type.LONG, Type.FLOAT, Type.DOUBLE)
                        ) {
                            InstructionUtil.box(returnType)?.let { il.add(it) }
                        }
                        // 调用 CallbackInfo.setReturnValue（现在栈顶是装箱后的返回值，下面是 CallbackInfo）
                        il.add(
                            MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                Type.getInternalName(CallbackInfo::class.java),
                                "setReturnValue",
                                "(Ljava/lang/Object;)V",
                                false,
                            ),
                        )
                    }
                }

                // 生成调用 ASM 方法的指令
                AsmMethodCallGenerator.generateMethodCall(
                    il,
                    asmMethod,
                    asmInfo,
                    target,
                    callbackVarIndex,
                )

                // 如果使用 CallbackInfo 且有返回值，检查并应用修改后的返回值
                if (needsCallbackInfo && callbackVarIndex != null && returnType != Type.VOID_TYPE && returnVarIndex != null) {
                    // 获取修改后的返回值
                    il.add(VarInsnNode(Opcodes.ALOAD, callbackVarIndex))
                    il.add(
                        MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            Type.getInternalName(CallbackInfo::class.java),
                            "getReturnValue",
                            "()Ljava/lang/Object;",
                            false,
                        ),
                    )
                    // 检查是否为 null（如果没有被修改）
                    val notModifiedLabel = LabelNode()
                    val endLabel = LabelNode()
                    il.add(InsnNode(Opcodes.DUP))
                    il.add(JumpInsnNode(Opcodes.IFNULL, notModifiedLabel))
                    // 类型转换并存储
                    when (returnType.sort) {
                        Type.BOOLEAN -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false))
                            il.add(VarInsnNode(Opcodes.ISTORE, returnVarIndex))
                        }
                        Type.BYTE -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false))
                            il.add(VarInsnNode(Opcodes.ISTORE, returnVarIndex))
                        }
                        Type.SHORT -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false))
                            il.add(VarInsnNode(Opcodes.ISTORE, returnVarIndex))
                        }
                        Type.INT -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false))
                            il.add(VarInsnNode(Opcodes.ISTORE, returnVarIndex))
                        }
                        Type.CHAR -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false))
                            il.add(VarInsnNode(Opcodes.ISTORE, returnVarIndex))
                        }
                        Type.LONG -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false))
                            il.add(VarInsnNode(Opcodes.LSTORE, returnVarIndex))
                        }
                        Type.FLOAT -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false))
                            il.add(VarInsnNode(Opcodes.FSTORE, returnVarIndex))
                        }
                        Type.DOUBLE -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"))
                            il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false))
                            il.add(VarInsnNode(Opcodes.DSTORE, returnVarIndex))
                        }
                        else -> {
                            il.add(TypeInsnNode(Opcodes.CHECKCAST, returnType.internalName))
                            il.add(VarInsnNode(Opcodes.ASTORE, returnVarIndex))
                        }
                    }
                    il.add(JumpInsnNode(Opcodes.GOTO, endLabel))
                    il.add(notModifiedLabel)
                    il.add(InsnNode(Opcodes.POP)) // 弹出 null
                    il.add(endLabel)
                    // 加载修改后的返回值（或原始值）
                    InstructionUtil.loadParam(returnType, returnVarIndex).let { il.add(it) }
                } else if (returnType != Type.VOID_TYPE && returnVarIndex != null) {
                    // 如果没有使用 CallbackInfo，重新加载原始返回值
                    InstructionUtil.loadParam(returnType, returnVarIndex).let { il.add(it) }
                }

                // 如果方法有返回值且目标方法是 void，需要弹出
                if (Type.getReturnType(asmMethod) != Type.VOID_TYPE && returnType == Type.VOID_TYPE) {
                    il.add(InsnNode(Opcodes.POP))
                }

                instructions.insertBefore(insn, il)
                transformed = true
            }
        }

        return transformed
    }

    /**
     * 分配局部变量索引
     * @param existingVarIndex 已分配的局部变量索引（如果存在），新变量会分配在这个之后
     * @return 返回分配的局部变量的起始索引
     */
    private fun allocateLocalVariable(
        target: MethodNode,
        type: Type,
        existingVarIndex: Int? = null,
    ): Int {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var varIndex = if (isStatic) 0 else 1

        // 计算参数占用的局部变量数量
        val paramTypes = Type.getArgumentTypes(target.desc)
        for (paramType in paramTypes) {
            varIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        // 查找所有局部变量的最大索引
        for (localVar in target.localVariables) {
            val needsDoubleSlot = localVar.desc == "J" || localVar.desc == "D"
            val end = localVar.index + (if (needsDoubleSlot) 2 else 1)
            varIndex = maxOf(varIndex, end)
        }

        // 查找所有指令中的局部变量引用
        for (insn in target.instructions.toArray()) {
            if (insn is VarInsnNode) {
                val needsDoubleSlot =
                    when (insn.opcode) {
                        Opcodes.LLOAD, Opcodes.LSTORE, Opcodes.DLOAD, Opcodes.DSTORE -> true
                        else -> false
                    }
                val end = insn.`var` + (if (needsDoubleSlot) 2 else 1)
                varIndex = maxOf(varIndex, end)
            }
        }

        // 如果已有分配的变量，从它之后开始
        if (existingVarIndex != null) {
            // existingVarIndex 是已分配变量的起始索引，需要加上它的大小
            // 为了安全，假设它最多占用2个槽
            varIndex = maxOf(varIndex, existingVarIndex + 2)
        }

        // 返回分配的起始索引（在分配之前）
        val allocatedIndex = varIndex

        // 为当前类型分配空间
        if (type.sort == Type.LONG || type.sort == Type.DOUBLE) {
            varIndex += 2
        } else {
            varIndex += 1
        }

        return allocatedIndex
    }

    companion object {
        private val RETURN_OPS =
            setOf(
                Opcodes.RETURN,
                Opcodes.IRETURN,
                Opcodes.LRETURN,
                Opcodes.FRETURN,
                Opcodes.DRETURN,
                Opcodes.ARETURN,
            )
    }
}
