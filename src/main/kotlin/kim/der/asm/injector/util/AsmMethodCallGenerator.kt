/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.util

import kim.der.asm.data.AsmInfo
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ASM 方法调用生成器
 * 生成调用 ASM 方法的字节码指令
 *
 * @author Dr (dr@der.kim)
 */
object AsmMethodCallGenerator {
    /**
     * 生成调用 ASM 方法的指令
     *
     * @param il 指令列表
     * @param asmMethod ASM 方法
     * @param asmInfo ASM 信息
     * @param targetMethod 目标方法
     * @param callbackVarIndex CallbackInfo 局部变量索引（如果存在）
     */
    fun generateMethodCall(
        il: InsnList,
        asmMethod: Method,
        asmInfo: AsmInfo,
        targetMethod: MethodNode,
        callbackVarIndex: Int? = null,
        targetClassName: String? = null,
    ) {
        val instanceType = Type.getType(asmInfo.asmClass)
        val isKotlinObject = isKotlinObject(asmInfo)
        val isMethodStatic = (asmMethod.modifiers and Modifier.STATIC) != 0
        val targetIsStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0

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

        // 如果需要实例调用，加载实例
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
                // 普通类：使用单例模式（参考 Mixin-master）
                // 在目标类中创建一个静态字段来缓存 Mixin 实例
                val targetClassInternalName =
                    targetClassName?.replace('.', '/')
                        ?: asmInfo.targets.firstOrNull()?.replace('.', '/')
                        ?: instanceType.internalName // 回退到 Mixin 类本身

                val singletonFieldName = "\$asmInstance\$${asmInfo.asmClass.simpleName}"
                val singletonFieldDesc = "L${instanceType.internalName};"

                // 加载或创建单例实例
                val notNullLabel = LabelNode()
                val endLabel = LabelNode()

                // 尝试从静态字段加载实例
                il.add(
                    FieldInsnNode(
                        Opcodes.GETSTATIC,
                        targetClassInternalName,
                        singletonFieldName,
                        singletonFieldDesc,
                    ),
                )
                il.add(InsnNode(Opcodes.DUP))
                il.add(JumpInsnNode(Opcodes.IFNONNULL, notNullLabel))

                // 如果为 null，创建新实例并存储
                il.add(InsnNode(Opcodes.POP)) // 弹出 null
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
                // 存储到静态字段
                il.add(InsnNode(Opcodes.DUP))
                il.add(
                    FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        targetClassInternalName,
                        singletonFieldName,
                        singletonFieldDesc,
                    ),
                )

                il.add(JumpInsnNode(Opcodes.GOTO, endLabel))
                il.add(notNullLabel)
                // 现在栈顶是实例（无论是新创建的还是从字段加载的）
                il.add(endLabel)
            }
        }

        // 加载 CallbackInfo（如果需要）
        val needsCallbackInfo = callbackVarIndex != null
        if (needsCallbackInfo) {
            il.add(VarInsnNode(Opcodes.ALOAD, callbackVarIndex))
        }

        // 加载其他参数
        val targetClassInternalName =
            targetClassName?.replace('.', '/')
                ?: asmInfo.targets.firstOrNull()?.replace('.', '/')
        ParameterMapper.loadParameters(il, targetMethod, asmMethod, needsCallbackInfo, targetClassInternalName)

        // 调用方法
        il.add(
            MethodInsnNode(
                if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 检查方法是否需要 CallbackInfo 参数
     */
    fun needsCallbackInfo(asmMethod: Method): Boolean =
        asmMethod.parameterTypes.isNotEmpty() &&
            asmMethod.parameterTypes[0] == kim.der.asm.api.annotation.CallbackInfo::class.java

    /**
     * 生成创建 CallbackInfo 实例的指令
     *
     * @param il 指令列表
     */
    fun generateCallbackInfoCreation(il: InsnList) {
        il.add(TypeInsnNode(Opcodes.NEW, Type.getInternalName(kim.der.asm.api.annotation.CallbackInfo::class.java)))
        il.add(InsnNode(Opcodes.DUP))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                Type.getInternalName(kim.der.asm.api.annotation.CallbackInfo::class.java),
                "<init>",
                "()V",
                false,
            ),
        )
    }

    /**
     * 检查是否需要弹出 ASM 方法的返回值
     * 当 ASM 方法有返回值但目标方法是 void 时，需要弹出返回值
     */
    fun needsPopReturnValue(
        asmMethod: Method,
        targetMethod: MethodNode,
    ): Boolean =
        Type.getReturnType(asmMethod) != Type.VOID_TYPE &&
            Type.getReturnType(targetMethod.desc) == Type.VOID_TYPE

    /**
     * 检查是否是 Kotlin object（有 INSTANCE 字段）
     */
    private fun isKotlinObject(asmInfo: AsmInfo): Boolean =
        try {
            val instanceField = asmInfo.asmClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.get(null) != null
        } catch (e: Exception) {
            false
        }
}
