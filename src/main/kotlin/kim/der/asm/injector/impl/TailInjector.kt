/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.impl.TailInjector.Companion.RETURN_OPS
import kim.der.asm.injector.util.AsmMethodCallGenerator
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * TAIL 注入器。
 *
 * 在目标方法的返回指令之前插入 ASM 方法调用。当前实现会对每个 RETURN 位置插入调用副本；
 * 若方法没有 RETURN 指令，则退回到方法末尾插入。非 `void` 目标方法使用 [CallbackInfo] 首参时，
 * 注入器会把当前返回值预置到回调对象，并在 handler 调用后回写回调中的返回值。
 * 当 [AsmInject.cancellable] 为 `true` 时，TAIL handler 可通过 [CallbackInfo.setReturnValue] 或
 * [CallbackInfo.cancel] 标记取消；取消分支会直接返回回调值，不再继续后续 TAIL 注入逻辑。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class TailInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在目标方法尾部注入 ASM 调用。
     *
     * @param target 目标方法
     * @return 成功插入指令后返回 `true`
     * @throws RuntimeException 参数映射或字节码结构不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    override fun injectCount(target: MethodNode): Int {
        val injectAnnotation = asmMethod.getAnnotation(AsmInject::class.java)
        val needsCallbackInfo = AsmMethodCallGenerator.needsCallbackInfo(asmMethod)
        val returnType = Type.getReturnType(target.desc)
        val instructions = target.instructions
        var injectionCount = 0

        for (insn in instructions.toArray()) {
            if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                instructions.insertBefore(
                    insn,
                    buildTailInjection(
                        target = target,
                        returnType = returnType,
                        needsCallbackInfo = needsCallbackInfo,
                        cancellable = injectAnnotation?.cancellable == true,
                    ),
                )
                injectionCount++
            }
        }

        // 如果没有找到 RETURN，在最后添加
        if (injectionCount == 0 && instructions.size() > 0) {
            instructions.insertBefore(
                instructions.last,
                buildTailInjection(
                    target = target,
                    returnType = returnType,
                    needsCallbackInfo = needsCallbackInfo,
                    cancellable = injectAnnotation?.cancellable == true,
                ),
            )
            injectionCount = 1
        } else if (instructions.size() == 0) {
            instructions.add(
                buildTailInjection(
                    target = target,
                    returnType = returnType,
                    needsCallbackInfo = needsCallbackInfo,
                    cancellable = injectAnnotation?.cancellable == true,
                ),
            )
            injectionCount = 1
        }

        return injectionCount
    }

    /**
     * 构造单个 TAIL 注入点的指令。
     *
     * 非 `void` 返回点进入该方法时，原返回值已经在操作数栈顶。方法会先把它保存到局部变量，
     * 再创建可选回调、调用 handler，最后把需要返回的值重新压回栈顶。
     *
     * @param target 目标方法
     * @param returnType 目标方法返回类型
     * @param needsCallbackInfo handler 是否需要回调首参
     * @param cancellable 回调是否允许取消
     * @return 可插入到目标返回点前的指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun buildTailInjection(
        target: MethodNode,
        returnType: Type,
        needsCallbackInfo: Boolean,
        cancellable: Boolean,
    ): InsnList {
        val il = InsnList()
        var returnVarIndex: Int? = null

        if (returnType != Type.VOID_TYPE) {
            returnVarIndex = allocateLocalVariable(target, returnType)
            il.add(storeReturnValue(returnType, returnVarIndex))
        }

        var callbackVarIndex: Int? = null
        if (needsCallbackInfo) {
            AsmMethodCallGenerator.generateCallbackInfoCreation(
                il = il,
                asmMethod = asmMethod,
                cancellable = cancellable,
                returnValueType = returnType.takeIf { it != Type.VOID_TYPE },
                returnValueVarIndex = returnVarIndex,
            )
            callbackVarIndex =
                allocateLocalVariable(
                    target = target,
                    type = Type.getType(CallbackInfo::class.java),
                    existingVarIndex = returnVarIndex,
                    existingType = returnType.takeIf { it != Type.VOID_TYPE },
                )
            il.add(VarInsnNode(Opcodes.ASTORE, callbackVarIndex))
        }

        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            target,
            callbackVarIndex,
        )

        // TAIL handler 的返回值不参与目标方法返回，必须始终丢弃以保持返回值栈顶不变。
        if (Type.getReturnType(asmMethod) != Type.VOID_TYPE) {
            AsmMethodCallGenerator.generatePopReturnValue(il, asmMethod)
        }

        if (needsCallbackInfo && callbackVarIndex != null) {
            if (returnType != Type.VOID_TYPE && returnVarIndex != null) {
                applyCallbackReturnValue(il, callbackVarIndex, returnType, returnVarIndex)
                if (cancellable) {
                    addCancellationReturn(il, callbackVarIndex, returnType)
                }
            } else if (cancellable) {
                addCancellationReturn(il, callbackVarIndex, returnType)
            }
        } else if (returnType != Type.VOID_TYPE && returnVarIndex != null) {
            il.add(InstructionUtil.loadParam(returnType, returnVarIndex))
        }

        return il
    }

    /**
     * 构造保存返回值到局部变量的指令。
     *
     * @param returnType 返回值类型
     * @param varIndex 局部变量槽位
     * @return 对应 `xSTORE` 指令
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun storeReturnValue(
        returnType: Type,
        varIndex: Int,
    ): VarInsnNode =
        when (returnType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> VarInsnNode(Opcodes.ISTORE, varIndex)
            Type.LONG -> VarInsnNode(Opcodes.LSTORE, varIndex)
            Type.FLOAT -> VarInsnNode(Opcodes.FSTORE, varIndex)
            Type.DOUBLE -> VarInsnNode(Opcodes.DSTORE, varIndex)
            else -> VarInsnNode(Opcodes.ASTORE, varIndex)
        }

    /**
     * 将回调对象中的返回值写回保存返回值的局部变量。
     *
     * @param il 指令列表
     * @param callbackVarIndex 回调对象槽位
     * @param returnType 目标返回类型
     * @param returnVarIndex 保存返回值的槽位
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun applyCallbackReturnValue(
        il: InsnList,
        callbackVarIndex: Int,
        returnType: Type,
        returnVarIndex: Int,
    ) {
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
        InstructionUtil.unbox(returnType).forEach { il.add(it) }
        il.add(storeReturnValue(returnType, returnVarIndex))
        il.add(InstructionUtil.loadParam(returnType, returnVarIndex))
    }

    /**
     * 追加可取消 TAIL 的提前返回分支。
     *
     * @param il 指令列表
     * @param callbackVarIndex 回调对象槽位
     * @param returnType 目标返回类型
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun addCancellationReturn(
        il: InsnList,
        callbackVarIndex: Int,
        returnType: Type,
    ) {
        val continueLabel = LabelNode()
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
        il.add(JumpInsnNode(Opcodes.IFEQ, continueLabel))
        il.add(InstructionUtil.makeReturn(returnType))
        il.add(continueLabel)
    }

    /**
     * 分配新的局部变量槽位。
     *
     * 该方法根据目标方法参数和返回值占用的槽位推算可用位置，适合为 TAIL 注入创建 CallbackInfo 临时变量。
     *
     * @param target 目标方法
     * @param type 待分配局部变量的类型
     * @param existingVarIndex 已分配局部变量起始槽位
     * @param existingType 已分配局部变量类型
     * @return 新局部变量的起始槽位
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    private fun allocateLocalVariable(
        target: MethodNode,
        type: Type,
        existingVarIndex: Int? = null,
        existingType: Type? = null,
    ): Int {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var varIndex = if (isStatic) 0 else 1

        // 计算参数占用的局部变量数量
        val paramTypes = Type.getArgumentTypes(target.desc)
        for (paramType in paramTypes) {
            varIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        // 返回类型占用的局部变量数量
        val returnType = Type.getReturnType(target.desc)
        if (returnType != Type.VOID_TYPE) {
            varIndex += if (returnType.sort == Type.LONG || returnType.sort == Type.DOUBLE) 2 else 1
        }

        if (existingVarIndex != null && existingType != null) {
            varIndex =
                maxOf(
                    varIndex,
                    existingVarIndex + if (existingType.sort == Type.LONG || existingType.sort == Type.DOUBLE) 2 else 1,
                )
        }

        return varIndex
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
