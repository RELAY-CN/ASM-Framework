/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyReturnValue 注入器。
 *
 * 在非 void 方法的返回指令前保存原始返回值，并调用 ASM 方法生成替换返回值。
 * void 方法没有可修改的返回值，因此会直接返回未修改。默认修改全部非 void 返回点；
 * 当 [ordinal] 为非负数时，只修改对应序号的返回点。
 *
 * @param ordinal 返回点序号；负数表示修改全部非 void 返回点
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyReturnValueInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val ordinal: Int = -1,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在目标方法返回前修改返回值。
     *
     * @param target 目标方法
     * @return 至少命中一个非 void 返回点并插入修改逻辑时返回 `true`
     * @throws IllegalArgumentException ASM 方法参数或返回类型与目标方法不匹配时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean {
        val returnType = Type.getReturnType(target.desc)

        // 如果返回类型是 void，无法修改返回值
        if (returnType == Type.VOID_TYPE) {
            return false
        }

        val instructions = target.instructions
        var transformed = false

        // 查找所有 RETURN 指令（不包括 void return）
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        for (insn in insns) {
            if (insn is InsnNode && insn.opcode in RETURN_OPS && insn.opcode != Opcodes.RETURN) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }

                val il = InsnList()

                // 在 RETURN 之前，栈顶应该是返回值
                // 参考 Mixin 的实现：使用 DUP 复制返回值，然后保存到局部变量
                val returnVar = allocateLocalVariable(target, returnType)

                // 复制返回值（使用 DUP 或 DUP2，取决于返回值类型的大小）
                val dupOpcode = if (returnType.size == 1) Opcodes.DUP else Opcodes.DUP2
                il.add(InsnNode(dupOpcode))

                // 保存复制的返回值到局部变量
                saveReturnValue(il, returnType, returnVar)

                // 检查 ASM 方法的参数
                val asmParamTypes = Type.getArgumentTypes(asmMethod)
                validateHandlerSignature(target, returnType, asmParamTypes)

                // ASM 方法的参数应该是：
                // 1. 第一个参数（可选）：原始返回值
                // 2. 后续参数（可选）：目标方法的参数

                // 确定第一个参数是否是返回值
                val firstParamIsReturnValue = asmParamTypes.isNotEmpty() && asmParamTypes[0] == returnType

                // 获取 ASM 实例并生成调用
                val instanceType = Type.getType(asmInfo.asmClass)
                val isKotlinObject = isKotlinObject()
                val isMethodStatic = (asmMethod.modifiers and Modifier.STATIC) != 0

                // 只有方法本身是静态的（例如 @JvmStatic 生成的方法）才能使用 INVOKESTATIC。
                val useStaticCall = isMethodStatic

                if (!useStaticCall) {
                    if (isKotlinObject) {
                        // Kotlin object：加载 INSTANCE 字段
                        il.add(
                            FieldInsnNode(
                                Opcodes.GETSTATIC,
                                instanceType.internalName,
                                "INSTANCE",
                                "L${instanceType.internalName};",
                            ),
                        )
                    } else {
                        // 普通类：创建新实例
                        il.add(TypeInsnNode(Opcodes.NEW, instanceType.internalName))
                        il.add(InsnNode(Opcodes.DUP))
                        il.add(
                            MethodInsnNode(
                                Opcodes.INVOKESPECIAL,
                                instanceType.internalName,
                                "<init>",
                                "()V",
                                false,
                            ),
                        )
                    }
                }

                // 如果第一个参数是返回值，从保存的局部变量加载返回值
                if (firstParamIsReturnValue) {
                    loadReturnValueForModification(il, returnType, returnVar)
                }

                // 计算需要加载的目标方法参数数量
                val targetParamCount =
                    if (firstParamIsReturnValue) {
                        // 第一个参数是返回值，后续参数是目标方法的参数
                        asmParamTypes.size - 1
                    } else {
                        // 第一个参数不是返回值，所有参数都是目标方法的参数
                        asmParamTypes.size
                    }

                // 加载目标方法的参数（只加载需要的数量）
                if (targetParamCount > 0) {
                    val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
                    var paramVarIndex = if (isStatic) 0 else 1
                    val targetParamTypes = Type.getArgumentTypes(target.desc)

                    for (i in 0 until targetParamCount) {
                        val paramType = targetParamTypes[i]
                        InstructionUtil.loadParam(paramType, paramVarIndex).let { il.add(it) }
                        paramVarIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
                    }
                } else if (!firstParamIsReturnValue && asmParamTypes.isEmpty()) {
                    // 如果 ASM 方法没有参数，但需要返回值，加载返回值
                    loadReturnValueForModification(il, returnType, returnVar)
                }

                // 调用 ASM 方法修改返回值
                // ASM 方法应该接收原始返回值并返回修改后的值
                val asmReturnType = Type.getReturnType(asmMethod)
                if (asmReturnType != returnType) {
                    throw IllegalArgumentException(
                        "ASM method return type ($asmReturnType) must match target method return type ($returnType)",
                    )
                }

                il.add(
                    MethodInsnNode(
                        if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                        instanceType.internalName,
                        asmMethod.name,
                        Type.getMethodDescriptor(asmMethod),
                        false,
                    ),
                )

                // 修改后的返回值现在在栈顶，可以直接返回
                // 替换原来的 RETURN（修改后的返回值已经在栈顶）
                instructions.insertBefore(insn, il)
                transformed = true
            }
        }

        return transformed
    }

    private fun matchesOrdinal(currentOrdinal: Int): Boolean = ordinal < 0 || currentOrdinal == ordinal

    private fun validateHandlerSignature(
        target: MethodNode,
        returnType: Type,
        asmParamTypes: Array<Type>,
    ) {
        val asmReturnType = Type.getReturnType(asmMethod)
        if (asmReturnType != returnType) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} return type ($asmReturnType) must match target method ${target.name}${target.desc} return type ($returnType)",
            )
        }

        val firstParamIsReturnValue = asmParamTypes.isNotEmpty() && asmParamTypes[0] == returnType
        val targetParamStart = if (firstParamIsReturnValue) 1 else 0
        val expectedTargetParams = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - targetParamStart

        if (requestedTargetParamCount > expectedTargetParams.size) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${expectedTargetParams.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = expectedTargetParams[index]
            val actual = asmParamTypes[targetParamStart + index]
            if (actual != expected) {
                throw IllegalArgumentException(
                    "ASM method ${asmMethod.name} parameter #${targetParamStart + index} ($actual) " +
                        "must match target method ${target.name}${target.desc} parameter #$index ($expected)",
                )
            }
        }
    }

    /**
     * 保存返回值到局部变量
     */
    private fun saveReturnValue(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        when (returnType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(VarInsnNode(Opcodes.ISTORE, varIndex))
            }
            Type.LONG -> {
                il.add(VarInsnNode(Opcodes.LSTORE, varIndex))
            }
            Type.FLOAT -> {
                il.add(VarInsnNode(Opcodes.FSTORE, varIndex))
            }
            Type.DOUBLE -> {
                il.add(VarInsnNode(Opcodes.DSTORE, varIndex))
            }
            else -> {
                il.add(VarInsnNode(Opcodes.ASTORE, varIndex))
            }
        }
    }

    /**
     * 加载返回值用于修改
     */
    private fun loadReturnValueForModification(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        // 加载返回值
        InstructionUtil.loadParam(returnType, varIndex).let { il.add(it) }
    }

    /**
     * 分配局部变量索引
     * 确保返回的索引不会与现有局部变量冲突
     */
    private fun allocateLocalVariable(
        target: MethodNode,
        type: Type,
    ): Int {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        // 计算所有参数占用的局部变量
        val paramTypes = Type.getArgumentTypes(target.desc)
        for (paramType in paramTypes) {
            maxIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        // 查找所有局部变量的最大索引
        for (localVar in target.localVariables) {
            val needsDoubleSlot = needsDoubleSlot(localVar.desc)
            val end = localVar.index + (if (needsDoubleSlot) 2 else 1)
            maxIndex = maxOf(maxIndex, end)
        }

        // 查找所有指令中的局部变量引用的最大索引
        for (insn in target.instructions.toArray()) {
            if (insn is VarInsnNode) {
                val needsDoubleSlot =
                    when (insn.opcode) {
                        Opcodes.LLOAD, Opcodes.LSTORE, Opcodes.DLOAD, Opcodes.DSTORE -> true
                        else -> false
                    }
                val end = insn.`var` + (if (needsDoubleSlot) 2 else 1)
                maxIndex = maxOf(maxIndex, end)
            }
        }

        // 为返回值类型分配额外的空间（如果需要双槽）
        // 注意：这里直接使用 maxIndex，确保不会与现有局部变量冲突
        val returnVarIndex = maxIndex
        if (needsDoubleSlot(type.descriptor)) {
            maxIndex += 2
        } else {
            maxIndex += 1
        }

        return returnVarIndex
    }

    /**
     * 检查是否需要双槽
     */
    private fun needsDoubleSlot(desc: String): Boolean = desc == "J" || desc == "D" || desc.startsWith("J") || desc.startsWith("D")

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
