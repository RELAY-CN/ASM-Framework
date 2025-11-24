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
 * ModifyArg 注入器
 * 修改方法参数
 *
 * @author Dr (dr@der.kim)
 */
class ModifyArgInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val argIndex: Int,
) : AbstractAsmInjector(method, asmInfo) {
    override fun inject(target: MethodNode): Boolean {
        val targetParamTypes = Type.getArgumentTypes(target.desc)

        // 验证参数索引
        if (argIndex < 0 || argIndex >= targetParamTypes.size) {
            throw IllegalArgumentException("Invalid argument index: $argIndex (method has ${targetParamTypes.size} parameters)")
        }

        val paramType = targetParamTypes[argIndex]

        // 直接在方法开头修改参数
        return modifyParameterAtMethodStart(target, paramType, argIndex)
    }

    /**
     * 在方法开头修改参数
     */
    private fun modifyParameterAtMethodStart(
        target: MethodNode,
        paramType: Type,
        argIndex: Int,
    ): Boolean {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        val il = InsnList()

        // 计算参数在局部变量中的索引
        var varIndex = if (isStatic) 0 else 1
        val paramTypes = Type.getArgumentTypes(target.desc)

        for (i in 0 until argIndex) {
            val pType = paramTypes[i]
            varIndex += if (pType.sort == Type.LONG || pType.sort == Type.DOUBLE) 2 else 1
        }

        // 加载原始参数值用于 ASM 方法调用（直接加载，不需要保存到临时变量）
        loadFromVariable(il, paramType, varIndex)

        // 调用 ASM 方法修改参数
        // ASM 方法应该接收原始参数值并返回修改后的值
        val asmReturnType = Type.getReturnType(asmMethod)
        if (asmReturnType != paramType) {
            throw IllegalArgumentException("ASM method return type ($asmReturnType) must match parameter type ($paramType)")
        }

        // 创建模拟方法节点，只有一个参数（要修改的参数）
        val mockTarget = createMockMethodNode(target, argIndex, paramType)

        // 获取 ASM 实例并生成调用
        val instanceType =
            Type
                .getType(asmInfo.asmClass)
        val isKotlinObject = isKotlinObject()
        val isMethodStatic = (asmMethod.modifiers and Modifier.STATIC) != 0
        val targetIsStatic = (target.access and Opcodes.ACC_STATIC) != 0

        // 确定调用方式
        val useStaticCall =
            if (isMethodStatic) {
                // 方法本身是静态的（@JvmStatic），使用 INVOKESTATIC
                true
            } else if (isKotlinObject && targetIsStatic) {
                // Kotlin object 的方法，但目标方法是静态的，转换为静态调用
                true
            } else {
                // 需要实例调用
                false
            }

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
                il.add(
                    TypeInsnNode(Opcodes.NEW, instanceType.internalName),
                )
                il.add(
                    InsnNode(Opcodes.DUP),
                )
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

        // 参数已经在栈顶（刚刚加载的原始参数值）
        il.add(
            MethodInsnNode(
                if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type
                    .getMethodDescriptor(asmMethod),
                false,
            ),
        )

        // 保存修改后的值回参数位置（ASM 方法的返回值现在在栈顶）
        when (paramType.sort) {
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

        // 在方法开头插入
        if (target.instructions.size() == 0) {
            target.instructions.add(il)
        } else {
            target.instructions.insertBefore(target.instructions.first, il)
        }

        return true
    }

    /**
     * 保存到变量
     */
    private fun saveToVariable(
        il: InsnList,
        paramType: Type,
        fromIndex: Int,
        toIndex: Int,
    ) {
        // 先加载源值
        loadFromVariable(il, paramType, fromIndex)

        // 保存到目标变量
        when (paramType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(VarInsnNode(Opcodes.ISTORE, toIndex))
            }
            Type.LONG -> {
                il.add(VarInsnNode(Opcodes.LSTORE, toIndex))
            }
            Type.FLOAT -> {
                il.add(VarInsnNode(Opcodes.FSTORE, toIndex))
            }
            Type.DOUBLE -> {
                il.add(VarInsnNode(Opcodes.DSTORE, toIndex))
            }
            else -> {
                il.add(VarInsnNode(Opcodes.ASTORE, toIndex))
            }
        }
    }

    /**
     * 从变量加载
     */
    private fun loadFromVariable(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        InstructionUtil.loadParam(paramType, varIndex).let { il.add(it) }
    }

    /**
     * 分配临时变量
     */
    private fun allocateTempVariable(
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

        // 查找最大局部变量索引
        for (localVar in target.localVariables) {
            val end = localVar.index + (if (needsDoubleSlot(localVar.desc)) 2 else 1)
            maxIndex = maxOf(maxIndex, end)
        }

        return maxIndex
    }

    /**
     * 释放临时变量
     */
    private fun freeTempVariable(
        target: MethodNode,
        varIndex: Int,
    ) {
        // 临时变量会在方法结束时自动释放，无需手动清理
        // 在需要优化局部变量使用的场景下，可以在这里添加变量复用逻辑
    }

    /**
     * 创建模拟方法节点用于参数加载
     */
    private fun createMockMethodNode(
        target: MethodNode,
        argIndex: Int,
        paramType: Type,
    ): MethodNode {
        // 创建一个只有一个参数（要修改的参数）的方法签名
        val mockDesc = Type.getMethodDescriptor(Type.getReturnType(asmMethod), paramType)
        return MethodNode(target.access, target.name, mockDesc, target.signature, target.exceptions?.toTypedArray())
    }

    /**
     * 检查是否需要双槽
     */
    private fun needsDoubleSlot(desc: String): Boolean = desc == "J" || desc == "D"
}
