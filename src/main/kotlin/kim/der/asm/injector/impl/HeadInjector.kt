/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
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
 * HEAD 注入器
 * 在方法开头注入代码
 *
 * @author Dr (dr@der.kim)
 */
class HeadInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    override fun inject(target: MethodNode): Boolean {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        val il = InsnList()

        // 创建 CallbackInfo 实例（如果需要）
        val needsCallbackInfo = AsmMethodCallGenerator.needsCallbackInfo(asmMethod)

        var callbackVarIndex: Int? = null
        if (needsCallbackInfo) {
            AsmMethodCallGenerator.generateCallbackInfoCreation(il)
            callbackVarIndex = allocateLocalVariable(target, Type.getType(CallbackInfo::class.java))
            il.add(VarInsnNode(Opcodes.ASTORE, callbackVarIndex))
        }

        // 生成调用 ASM 方法的指令
        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            target,
            callbackVarIndex,
        )

        // 如果方法有返回值且目标方法是 void，需要弹出
        if (AsmMethodCallGenerator.needsPopReturnValue(asmMethod, target)) {
            il.add(InsnNode(Opcodes.POP))
        }

        // 如果需要 CallbackInfo，检查是否需要取消
        if (needsCallbackInfo && callbackVarIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, callbackVarIndex))
            il.add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(CallbackInfo::class.java),
                    "isCancelled",
                    "()Z",
                    false,
                ),
            )
            val cancelLabel = LabelNode()
            il.add(JumpInsnNode(Opcodes.IFEQ, cancelLabel))

            /**
             * 如果取消，直接返回（不执行方法体）
             * 
             * 重要：这个 RETURN 指令是在 HEAD 注入中创建的，不会被 RETURN 注入处理
             * 原因：
             * 1. HEAD 注入在 RETURN 注入之后执行（见 TargetClassContext.applyAsm()）
             * 2. RETURN 注入只会在处理时存在的 RETURN 指令之前插入代码
             * 3. 当 HEAD 注入执行时，RETURN 注入已经完成，不会处理新创建的 RETURN 指令
             * 4. 这确保了当 HEAD 注入取消方法时，RETURN 注入不会被执行（符合 Mixin-master 的行为）
             */
            val returnType = Type.getReturnType(target.desc)
            if (returnType != Type.VOID_TYPE) {
                // 从 CallbackInfo 获取返回值
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
                
                // 检查返回值是否为 null
                val hasReturnValueLabel = LabelNode()
                val defaultReturnLabel = LabelNode()
                val endLabel = LabelNode()
                il.add(InsnNode(Opcodes.DUP))
                il.add(JumpInsnNode(Opcodes.IFNULL, defaultReturnLabel))
                
                // 如果有返回值，进行类型转换
                when (returnType.sort) {
                    Type.BOOLEAN -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false))
                    }
                    Type.BYTE -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false))
                    }
                    Type.SHORT -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false))
                    }
                    Type.INT -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false))
                    }
                    Type.CHAR -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false))
                    }
                    Type.LONG -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false))
                    }
                    Type.FLOAT -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false))
                    }
                    Type.DOUBLE -> {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"))
                        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false))
                    }
                    else -> {
                        // 对象类型，直接进行类型转换
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, returnType.internalName))
                    }
                }
                il.add(JumpInsnNode(Opcodes.GOTO, endLabel))
                
                // 如果没有返回值，弹出 DUP 的 Object，然后使用默认值
                il.add(defaultReturnLabel)
                il.add(InsnNode(Opcodes.POP)) // 弹出剩余的 Object（来自 DUP）
                when (returnType.sort) {
                    Type.BOOLEAN -> il.add(InsnNode(Opcodes.ICONST_0))
                    Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> il.add(InsnNode(Opcodes.ICONST_0))
                    Type.LONG -> il.add(InsnNode(Opcodes.LCONST_0))
                    Type.FLOAT -> il.add(InsnNode(Opcodes.FCONST_0))
                    Type.DOUBLE -> il.add(InsnNode(Opcodes.DCONST_0))
                    else -> il.add(InsnNode(Opcodes.ACONST_NULL))
                }
                
                il.add(endLabel)
            }
            // 直接返回，不执行方法体
            // 注意：这个 RETURN 指令不会被 RETURN 注入处理（因为 RETURN 注入已经完成）
            il.add(InstructionUtil.makeReturn(returnType))
            il.add(cancelLabel)
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
     * 分配局部变量索引
     */
    private fun allocateLocalVariable(
        target: MethodNode,
        type: Type,
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

        return varIndex
    }
}
