/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Slice
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.BytecodeUtil
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Redirect 注入器。
 *
 * 查找目标方法中的匹配方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、简单数组元素访问、数组长度、
 * 局部变量读取、局部变量待写入值、`CHECKCAST` 类型转换、`INSTANCEOF` 类型判断、条件跳转、switch selector、常量加载或抛异常点，
 * 并用 ASM 方法调用替换原指令或改写原指令消费的值。
 *
 * 方法调用和 `invokedynamic` 调用目标使用 `owner.name(desc)` 或 `name(desc)` 格式；`invokedynamic`
 * 会按 bootstrap owner、动态调用名或 bootstrap 方法名，以及动态调用点描述符匹配。字段读取目标使用
 * `owner.field:desc`、`field:desc` 或 `field` 格式。字段写入目标格式与字段读取相同，
 * 但需要将 [injectionPoint] 设置为 [InjectionPoint.FIELD_ASSIGN]。数组元素访问与数组长度使用 [InjectionPoint.FIELD]
 * 匹配产生数组引用的字段读取，并通过 [args] 中的 `array=get`、`array=set` 或 `array=length` 区分读取、写入与长度读取。
 * 构造器重定向可通过 [InjectionPoint.INVOKE] 与 `<init>` 目标匹配，也可通过 [InjectionPoint.NEW]
 * 与构造类型 internal name 或 binary name 匹配。类型转换使用 [InjectionPoint.CAST] 与类型 internal name 或 binary name 匹配；
 * 未指定类型目标时，会按 handler 返回类型筛选兼容的 `CHECKCAST` 候选。类型判断使用 [InjectionPoint.INSTANCEOF]
 * 与类型 internal name 或 binary name 匹配；未指定类型目标时，会匹配切片内全部 `INSTANCEOF` 判断。
 * 局部变量读取和待写入值使用 [InjectionPoint.LOAD] 或 [InjectionPoint.STORE]，可通过 [args] 中的 `index=N`
 * 或 `var=N` 按 JVM 局部变量槽位过滤。读取模式只替换这一次 `xLOAD` 读取结果，不写回局部槽位；写入模式会改写原 `xSTORE`
 * 消费前的待写入值。
 * 条件跳转使用 [InjectionPoint.JUMP] 与跳转操作码名或数字匹配；未指定跳转目标时，会匹配切片内全部条件跳转，`GOTO` 与 `JSR` 不支持重定向。
 * switch selector 使用 [InjectionPoint.SWITCH] 匹配 `tableswitch` 与 `lookupswitch` 消费前的 `Int` selector；该模式不使用目标签名。
 * 常量加载使用 [InjectionPoint.CONSTANT] 与常量文本匹配；未指定常量目标时，会按 handler 首参与返回类型筛选兼容常量。
 * 抛异常使用 [InjectionPoint.THROW] 匹配 `ATHROW` 前即将抛出的 [Throwable]；指定目标时只匹配直接构造后抛出的同类型异常。
 * handler 返回的异常对象会被原 `ATHROW` 继续抛出，不能跳过抛出。
 * 方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、局部变量读写、
 * 类型转换、类型判断、条件跳转、switch selector、常量加载与抛异常点重定向支持静态处理器、`@JvmStatic`
 * 处理器或 Kotlin `object` 实例处理器。处理器需先接收原调用、动态调用、构造器、字段访问、局部变量值、类型转换、类型判断、
 * 原条件跳转分支结果、原 switch selector、原常量值或即将抛出的异常需要的栈参数，后续可按顺序接收目标方法的部分参数。
 *
 * @param redirectTarget 要重定向的方法调用、动态调用、构造器调用、字段访问、构造类型、类型签名、跳转操作码、常量文本或直接构造异常类型；[InjectionPoint.LOAD] / [InjectionPoint.STORE] / [InjectionPoint.SWITCH] 不使用该参数
 * @param injectionPoint Redirect 的定位点类型；[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * 会强制按字段访问语义解析目标，[InjectionPoint.NEW] 会按构造类型解析目标，
 * [InjectionPoint.CAST] 会按类型转换语义解析目标，[InjectionPoint.INSTANCEOF] 会按类型判断语义解析目标，
 * [InjectionPoint.LOAD] / [InjectionPoint.STORE] 会按局部变量读写值语义解析目标，
 * [InjectionPoint.JUMP] 会按条件跳转语义解析目标，[InjectionPoint.SWITCH] 会按 switch selector 语义解析目标，
 * [InjectionPoint.CONSTANT] 会按常量加载语义解析目标，[InjectionPoint.THROW] 会按抛异常语义解析目标
 * @param ordinal 匹配点序号；负数表示重定向全部匹配点，当前用于方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、局部变量读写、类型转换、类型判断、条件跳转、switch selector、常量加载与抛异常点重定向
 * @param slice 切片范围；当前方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、局部变量读写、类型转换、类型判断、条件跳转、switch selector、常量加载与抛异常点重定向
 * 使用 [InjectionPoint.INVOKE] 边界缩小匹配范围
 * @param args 调用点附加参数；`array=get` 匹配数组元素读取，`array=set` 匹配数组元素写入，`array=length` 匹配数组长度；
 * [InjectionPoint.LOAD] / [InjectionPoint.STORE] 支持 `index=N` 或 `var=N` 槽位过滤
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class RedirectInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val redirectTarget: String,
    private val injectionPoint: InjectionPoint = InjectionPoint.INVOKE,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
    private val args: Array<String> = emptyArray(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 替换目标方法中的匹配调用点、动态调用点、构造器调用点、字段读取点、字段写入点、数组元素访问点、数组长度点、局部变量读写点、类型转换点、类型判断点、条件跳转点、switch selector、常量加载或抛异常点。
     *
     * @param target 目标方法
     * @return 至少替换一个调用点、动态调用点、构造器调用点、字段读取点、字段写入点、数组元素访问点、数组长度点、局部变量读写点、类型转换点、类型判断点、条件跳转点、switch selector、常量加载或抛异常点时返回 `true`
     * @throws IllegalArgumentException 目标方法调用或字段签名无法解析时抛出
     * @throws RuntimeException 替换调用、字段访问或返回值适配失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 替换目标方法中的匹配点并返回实际重定向数量。
     *
     * @param target 目标方法
     * @return 实际替换的调用点、动态调用点、构造器调用点、字段访问点、数组访问点、数组长度点、局部变量读写点、类型转换点、类型判断点、条件跳转点、switch selector、常量加载或抛异常点数量
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        val arrayAccessMode = arrayAccessMode()
        if (arrayAccessMode != null) {
            return injectArrayAccessCount(target, arrayAccessMode)
        }
        if (isJumpRedirect()) {
            return injectJumpCount(target)
        }
        if (isSwitchRedirect()) {
            return injectSwitchCount(target)
        }
        if (isThrowRedirect()) {
            return injectThrowCount(target)
        }
        if (isLoadRedirect()) {
            return injectLoadCount(target)
        }
        if (isStoreRedirect()) {
            return injectStoreCount(target)
        }
        if (isInstanceofRedirect()) {
            return injectInstanceofCount(target)
        }
        if (isCastRedirect()) {
            return injectCastCount(target)
        }
        if (isFieldAssignRedirect()) {
            return injectFieldAssignCount(target)
        }
        if (isFieldReadRedirect()) {
            return injectFieldReadCount(target)
        }
        if (isNewRedirect()) {
            return injectNewConstructorCount(target)
        }
        if (isConstantRedirect()) {
            return injectConstantCount(target)
        }

        val inferTarget = redirectTarget.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(redirectTarget)

        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException(
                "Invalid target method signature: $redirectTarget " +
                    "(parsed: owner=$targetOwner, name=$targetName, desc=$targetDesc)",
            )
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        var matchedOrdinal = 0

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }

            when {
                insn is MethodInsnNode &&
                    matchesRedirectMethodCandidate(target, insn, inferTarget, targetOwner, targetName, targetDesc) -> {
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }
                    if (insn.name == "<init>") {
                        replaceConstructorCall(target, instructions, insn)
                    } else {
                        replaceMethodCall(target, instructions, insn)
                    }
                    injectionCount++
                }
                insn is InvokeDynamicInsnNode &&
                    matchesRedirectInvokeDynamicCandidate(
                        target,
                        insn,
                        inferTarget,
                        targetOwner,
                        targetName,
                        targetDesc,
                    ) -> {
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }
                    replaceInvokeDynamicCall(target, instructions, insn)
                    injectionCount++
                }
            }
        }

        return injectionCount
    }

    /**
     * 判断当前命中序号是否满足注解声明的 ordinal 过滤。
     *
     * 负数 ordinal 表示不按序号过滤。
     *
     * @param currentOrdinal 当前候选点在同类重定向点中的命中序号
     * @return 当前候选点应被处理时返回 `true`
     */
    private fun matchesOrdinal(currentOrdinal: Int): Boolean = ordinal < 0 || currentOrdinal == ordinal

    /**
     * 判断普通方法调用候选是否应参与重定向。
     *
     * 显式目标模式按 owner、名称与描述符匹配；目标推断模式按 handler 签名兼容性筛选。
     *
     * @param target 目标方法
     * @param insn 候选普通方法调用指令
     * @param inferTarget 是否根据 handler 签名推断目标
     * @param targetOwner 目标 owner；推断模式下可为 `null`
     * @param targetName 目标方法名；推断模式下可为 `null`
     * @param targetDesc 目标方法描述符；推断模式下可为 `null`
     * @return 候选调用满足当前目标模式时返回 `true`
     */
    private fun matchesRedirectMethodCandidate(
        target: MethodNode,
        insn: MethodInsnNode,
        inferTarget: Boolean,
        targetOwner: String?,
        targetName: String?,
        targetDesc: String?,
    ): Boolean {
        if (!inferTarget) {
            return matchesTargetMethod(insn, targetOwner, targetName ?: return false, targetDesc ?: return false)
        }
        return canRedirectMethodCall(target, insn)
    }

    /**
     * 判断 `invokedynamic` 候选是否应参与重定向。
     *
     * 显式目标模式按 bootstrap owner、调用名或 bootstrap 名、描述符匹配；
     * 目标推断模式按 handler 签名兼容性筛选。
     *
     * @param target 目标方法
     * @param insn 候选 `invokedynamic` 指令
     * @param inferTarget 是否根据 handler 签名推断目标
     * @param targetOwner 目标 bootstrap owner；推断模式下可为 `null`
     * @param targetName 目标调用名或 bootstrap 方法名；推断模式下可为 `null`
     * @param targetDesc 目标动态调用描述符；推断模式下可为 `null`
     * @return 候选动态调用满足当前目标模式时返回 `true`
     */
    private fun matchesRedirectInvokeDynamicCandidate(
        target: MethodNode,
        insn: InvokeDynamicInsnNode,
        inferTarget: Boolean,
        targetOwner: String?,
        targetName: String?,
        targetDesc: String?,
    ): Boolean {
        if (!inferTarget) {
            return matchesTargetInvokeDynamic(insn, targetOwner, targetName ?: return false, targetDesc ?: return false)
        }
        return canRedirectInvokeDynamicCall(target, insn)
    }

    /**
     * 判断 handler 是否兼容候选普通方法调用或构造器调用。
     *
     * 该方法用于目标推断模式，签名校验失败的候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选普通方法调用指令
     * @return handler 签名可重定向该调用时返回 `true`
     */
    private fun canRedirectMethodCall(
        target: MethodNode,
        insn: MethodInsnNode,
    ): Boolean =
        runCatching {
            if (insn.name == "<init>") {
                validateConstructorHandlerSignature(target, insn)
            } else {
                validateHandlerSignature(target, insn)
            }
        }.isSuccess

    /**
     * 判断 handler 是否兼容候选 `invokedynamic` 调用。
     *
     * 该方法用于目标推断模式，签名校验失败的候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选 `invokedynamic` 指令
     * @return handler 签名可重定向该动态调用时返回 `true`
     */
    private fun canRedirectInvokeDynamicCall(
        target: MethodNode,
        insn: InvokeDynamicInsnNode,
    ): Boolean =
        runCatching {
            validateInvokeDynamicHandlerSignature(target, insn)
        }.isSuccess

    /**
     * 解析当前切片在指令数组中的起止范围。
     *
     * `from` 边界命中后从下一条指令开始，`to` 边界命中前结束；边界未命中时返回空范围。
     *
     * @param insns 目标方法指令数组
     * @return 左闭右开的指令范围
     */
    private fun resolveSliceRange(insns: Array<AbstractInsnNode>): Pair<Int, Int> {
        val startIndex =
            if (hasSliceBoundary(slice.from)) {
                val fromIndex = findSliceBoundaryIndex(insns, slice.from, 0) ?: return emptySlice(insns)
                fromIndex + 1
            } else {
                0
            }
        val endIndex =
            if (hasSliceBoundary(slice.to)) {
                findSliceBoundaryIndex(insns, slice.to, startIndex) ?: return emptySlice(insns)
            } else {
                insns.size
            }

        return startIndex to endIndex.coerceAtLeast(startIndex)
    }

    /**
     * 判断切片边界是否已声明目标。
     *
     * @param at 切片边界定位点
     * @return `target` 非空时返回 `true`
     */
    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    /**
     * 构造位于方法末尾的空切片范围。
     *
     * @param insns 目标方法指令数组
     * @return 左右边界都等于指令数量的空范围
     */
    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    /**
     * 查找切片边界方法调用在指令数组中的位置。
     *
     * 当前只支持 `INVOKE` 边界，可匹配普通方法调用或 `invokedynamic` 调用。
     *
     * @param insns 目标方法指令数组
     * @param at 切片边界定位点
     * @param startIndex 开始查找的指令下标
     * @return 边界指令下标；未命中时返回 `null`
     * @throws IllegalArgumentException 边界类型不是 [InjectionPoint.INVOKE] 或目标签名不完整时抛出
     */
    private fun findSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @Redirect: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid Redirect slice boundary method signature: ${at.target} " +
                    "(parsed: owner=$boundaryOwner, name=$boundaryName, desc=$boundaryDesc)",
            )
        }

        for (index in startIndex until insns.size) {
            val insn = insns[index]
            if (
                insn is MethodInsnNode &&
                matchesTargetMethod(insn, boundaryOwner, boundaryName, boundaryDesc)
            ) {
                return index
            }
            if (
                insn is InvokeDynamicInsnNode &&
                matchesTargetInvokeDynamic(insn, boundaryOwner, boundaryName, boundaryDesc)
            ) {
                return index
            }
        }

        return null
    }

    /**
     * 判断当前配置是否按字段读取语义重定向。
     *
     * 除显式 [InjectionPoint.FIELD] 外，兼容旧式 `field:desc` 目标格式自动进入字段读取模式。
     *
     * @return 当前配置表示字段读取重定向时返回 `true`
     */
    private fun isFieldReadRedirect(): Boolean =
        injectionPoint == InjectionPoint.FIELD ||
            (redirectTarget.contains(':') && !redirectTarget.contains('('))

    /**
     * 判断当前配置是否按字段写入语义重定向。
     *
     * @return 当前定位点为 [InjectionPoint.FIELD_ASSIGN] 时返回 `true`
     */
    private fun isFieldAssignRedirect(): Boolean = injectionPoint == InjectionPoint.FIELD_ASSIGN

    /**
     * 判断当前配置是否按 `INSTANCEOF` 结果重定向。
     *
     * @return 当前定位点为 [InjectionPoint.INSTANCEOF] 时返回 `true`
     */
    private fun isInstanceofRedirect(): Boolean = injectionPoint == InjectionPoint.INSTANCEOF

    /**
     * 判断当前配置是否按 `CHECKCAST` 结果重定向。
     *
     * @return 当前定位点为 [InjectionPoint.CAST] 时返回 `true`
     */
    private fun isCastRedirect(): Boolean = injectionPoint == InjectionPoint.CAST

    /**
     * 判断当前配置是否按条件跳转结果重定向。
     *
     * @return 当前定位点为 [InjectionPoint.JUMP] 时返回 `true`
     */
    private fun isJumpRedirect(): Boolean = injectionPoint == InjectionPoint.JUMP

    /**
     * 判断当前配置是否按 switch selector 重定向。
     *
     * @return 当前定位点为 [InjectionPoint.SWITCH] 时返回 `true`
     */
    private fun isSwitchRedirect(): Boolean = injectionPoint == InjectionPoint.SWITCH

    /**
     * 判断当前配置是否按即将抛出的异常对象重定向。
     *
     * @return 当前定位点为 [InjectionPoint.THROW] 时返回 `true`
     */
    private fun isThrowRedirect(): Boolean = injectionPoint == InjectionPoint.THROW

    /**
     * 判断当前配置是否按局部变量读取值重定向。
     *
     * @return 当前定位点为 [InjectionPoint.LOAD] 时返回 `true`
     */
    private fun isLoadRedirect(): Boolean = injectionPoint == InjectionPoint.LOAD

    /**
     * 判断当前配置是否按局部变量待写入值重定向。
     *
     * @return 当前定位点为 [InjectionPoint.STORE] 时返回 `true`
     */
    private fun isStoreRedirect(): Boolean = injectionPoint == InjectionPoint.STORE

    /**
     * 判断当前配置是否按对象构造表达式重定向。
     *
     * @return 当前定位点为 [InjectionPoint.NEW] 时返回 `true`
     */
    private fun isNewRedirect(): Boolean = injectionPoint == InjectionPoint.NEW

    /**
     * 判断当前配置是否按常量加载结果重定向。
     *
     * @return 当前定位点为 [InjectionPoint.CONSTANT] 时返回 `true`
     */
    private fun isConstantRedirect(): Boolean = injectionPoint == InjectionPoint.CONSTANT

    /**
     * 解析数组访问重定向模式。
     *
     * `args` 中声明 `array=get`、`array=set` 或 `array=length` 时进入数组读取、写入或长度重定向。
     *
     * @return 数组访问模式；未声明数组模式时返回 `null`
     * @throws IllegalArgumentException 声明了不支持的数组访问模式时抛出
     */
    private fun arrayAccessMode(): ArrayAccessMode? {
        val arrayArg = args.firstOrNull { it.trim().startsWith("array=") } ?: return null
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "get" -> ArrayAccessMode.GET
            "set" -> ArrayAccessMode.SET
            "length" -> ArrayAccessMode.LENGTH
            else -> throw IllegalArgumentException("Unsupported Redirect array access mode: $arrayArg")
        }
    }

    /**
     * 通过 [InjectionPoint.NEW] 重定向对象构造表达式。
     *
     * 该入口从 `NEW` 指令出发查找配对构造器调用，并用 handler 调用替换整段构造流程。
     *
     * @param target 目标方法
     * @return 实际重定向的构造表达式数量
     * @throws IllegalArgumentException NEW 目标为空、找不到配对构造器或 handler 签名不兼容时抛出
     */
    private fun injectNewConstructorCount(target: MethodNode): Int {
        val typeTarget = redirectTarget.replace('.', '/')
        if (typeTarget.isEmpty()) {
            throw IllegalArgumentException("Redirect NEW target type must not be empty")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is TypeInsnNode && insn.opcode == Opcodes.NEW && insn.desc == typeTarget) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                val constructorInsn = findConstructorInvocation(insn)
                replaceNewConstructorCall(target, instructions, insn, constructorInsn)
                injectionCount++
            }
        }

        return injectionCount
    }

    /**
     * 重定向简单数组元素访问或数组长度读取。
     *
     * 数组引用必须来自匹配字段的直接读取，复杂栈表达式不会被当前入口处理。
     *
     * @param target 目标方法
     * @param mode 数组访问模式
     * @return 实际重定向的数组访问数量
     * @throws IllegalArgumentException 数组字段目标不合法、目标字段不是数组或 handler 签名不兼容时抛出
     */
    private fun injectArrayAccessCount(
        target: MethodNode,
        mode: ArrayAccessMode,
    ): Int {
        val fieldTarget = parseFieldTarget(redirectTarget)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("Invalid target array field signature: $redirectTarget")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("Redirect array access target must be an array field: $redirectTarget")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        val targetOpcodes =
            when (mode) {
                ArrayAccessMode.GET -> ARRAY_READ_OPS
                ArrayAccessMode.SET -> ARRAY_WRITE_OPS
                ArrayAccessMode.LENGTH -> setOf(Opcodes.ARRAYLENGTH)
            }

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode !in targetOpcodes) {
                continue
            }

            val fieldInsn = findArrayFieldProducer(insn, fieldTarget) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            replaceArrayAccess(target, instructions, insn, fieldInsn, mode)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 重定向 `INSTANCEOF` 类型判断结果。
     *
     * 显式声明目标时只匹配对应类型；未声明目标时匹配切片内全部 `INSTANCEOF` 指令。
     *
     * @param target 目标方法
     * @return 实际重定向的类型判断数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectInstanceofCount(target: MethodNode): Int {
        val typeTarget = redirectTarget.replace('.', '/')
        val matchAnyTarget = typeTarget.isEmpty()

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is TypeInsnNode && insn.opcode == Opcodes.INSTANCEOF && (matchAnyTarget || insn.desc == typeTarget)) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceInstanceof(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    /**
     * 重定向 `CHECKCAST` 类型转换结果。
     *
     * 显式声明目标时按转换类型匹配；未声明目标时按 handler 返回值筛选可接收的转换结果。
     *
     * @param target 目标方法
     * @return 实际重定向的类型转换数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectCastCount(target: MethodNode): Int {
        val typeTarget = redirectTarget.replace('.', '/')
        val matchAnyTarget = typeTarget.isEmpty()

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is TypeInsnNode && insn.opcode == Opcodes.CHECKCAST && (matchAnyTarget || insn.desc == typeTarget)) {
                if (matchAnyTarget && !isCastReturnCompatible(Type.getObjectType(insn.desc))) {
                    continue
                }
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceCast(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    /**
     * 判断 handler 返回值是否能替代指定 `CHECKCAST` 结果。
     *
     * @param castType 原 `CHECKCAST` 目标类型
     * @return handler 返回值可作为转换结果时返回 `true`
     */
    private fun isCastReturnCompatible(castType: Type): Boolean =
        isReturnCompatible(castType, Type.getReturnType(asmMethod))

    /**
     * 重定向局部变量读取值。
     *
     * 该入口只替换当前 `xLOAD` 指令压入栈的值，不写回局部变量槽位；可通过 `index=N` 或 `var=N` 过滤槽位。
     *
     * @param target 目标方法
     * @return 实际重定向的局部变量读取数量
     * @throws IllegalArgumentException `At.target` 非空、槽位过滤或 handler 签名不合法时抛出
     */
    private fun injectLoadCount(target: MethodNode): Int {
        require(redirectTarget.isEmpty()) {
            "Redirect LOAD uses At.args index=N or var=N for local variable slot filtering, not At.target"
        }
        val localVariableIndex = parseLocalVariableIndex("LOAD")
        val handlerValueType = requireLocalHandlerValueType("LOAD")
        val instructions = target.instructions
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is VarInsnNode || insn.opcode !in LOAD_OPS) {
                continue
            }
            if (localVariableIndex != null && insn.`var` != localVariableIndex) {
                continue
            }
            if (!isLoadCompatibleWithHandler(insn.opcode, handlerValueType)) {
                continue
            }

            val resolvedValueType = resolveIndexedLocalValueType(target, insn.`var`, handlerValueType)
            if (localVariableIndex == null && handlerValueType.isReferenceType() && resolvedValueType == null) {
                continue
            }
            val valueType = resolvedValueType ?: handlerValueType
            if (localVariableIndex == null && !isLocalHandlerCompatible(valueType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateLocalHandlerSignature(target, valueType, "LOAD")
            val il = buildLocalValueRedirect(target, valueType, targetParamCount)
            instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 重定向局部变量待写入值。
     *
     * 该入口在 `xSTORE` 消费栈顶值前调用 handler，并把 handler 返回值交给原 store 指令继续写入。
     *
     * @param target 目标方法
     * @return 实际重定向的局部变量写入数量
     * @throws IllegalArgumentException `At.target` 非空、槽位过滤或 handler 签名不合法时抛出
     */
    private fun injectStoreCount(target: MethodNode): Int {
        require(redirectTarget.isEmpty()) {
            "Redirect STORE uses At.args index=N or var=N for local variable slot filtering, not At.target"
        }
        val localVariableIndex = parseLocalVariableIndex("STORE")
        val handlerValueType = requireLocalHandlerValueType("STORE")
        val instructions = target.instructions
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is VarInsnNode || insn.opcode !in STORE_OPS) {
                continue
            }
            if (localVariableIndex != null && insn.`var` != localVariableIndex) {
                continue
            }
            if (!isStoreCompatibleWithHandler(insn.opcode, handlerValueType)) {
                continue
            }

            val resolvedValueType = resolveIndexedLocalValueType(target, insn.`var`, handlerValueType)
            if (localVariableIndex == null && handlerValueType.isReferenceType() && resolvedValueType == null) {
                continue
            }
            val valueType = resolvedValueType ?: handlerValueType
            if (localVariableIndex == null && !isLocalHandlerCompatible(valueType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateLocalHandlerSignature(target, valueType, "STORE")
            val il = buildLocalValueRedirect(target, valueType, targetParamCount)
            instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 重定向常量加载结果。
     *
     * 显式声明目标时按常量文本匹配；未声明目标时按 handler 首参和返回值筛选兼容常量。
     *
     * @param target 目标方法
     * @return 实际重定向的常量加载数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectConstantCount(target: MethodNode): Int {
        val inferTarget = redirectTarget.isEmpty()
        val instructions = target.instructions
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (!BytecodeUtil.isConstant(insn)) {
                continue
            }
            if (!inferTarget && !BytecodeUtil.matchesConstantText(insn, redirectTarget)) {
                continue
            }

            val constantType = BytecodeUtil.getConstantType(insn) ?: continue
            if (inferTarget && !isConstantHandlerCompatible(target, constantType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateLocalHandlerSignature(target, constantType, "CONSTANT")
            val il = buildLocalValueRedirect(target, constantType, targetParamCount)
            instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 重定向条件跳转结果。
     *
     * handler 接收原分支结果并返回新的 boolean 结果，替代原条件跳转是否跳转的判断。
     *
     * @param target 目标方法
     * @return 实际重定向的条件跳转数量
     * @throws IllegalArgumentException 跳转目标不是条件跳转 opcode 或 handler 签名不兼容时抛出
     */
    private fun injectJumpCount(target: MethodNode): Int {
        val targetOpcode = parseJumpOpcodeTarget(redirectTarget)
        if (targetOpcode != null && targetOpcode !in CONDITIONAL_JUMP_OPS) {
            throw IllegalArgumentException(
                "Redirect JUMP target must be a conditional JVM jump opcode: $redirectTarget",
            )
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (
                insn !is JumpInsnNode ||
                insn.opcode !in CONDITIONAL_JUMP_OPS ||
                (targetOpcode != null && insn.opcode != targetOpcode)
            ) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            replaceJump(target, instructions, insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 重定向 switch selector。
     *
     * 该入口匹配 `tableswitch` 与 `lookupswitch` 消费前的 `Int` selector，不支持 `At.target` 过滤。
     *
     * @param target 目标方法
     * @return 实际重定向的 switch selector 数量
     * @throws IllegalArgumentException `At.target` 非空或 handler 签名不兼容时抛出
     */
    private fun injectSwitchCount(target: MethodNode): Int {
        if (redirectTarget.isNotEmpty()) {
            throw IllegalArgumentException("Redirect SWITCH does not support target")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TableSwitchInsnNode && insn !is LookupSwitchInsnNode) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            replaceSwitch(target, instructions, insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 重定向即将抛出的异常对象。
     *
     * 显式声明目标时只匹配直接构造后抛出的对应异常；未声明目标时按 handler 签名筛选 `ATHROW` 候选。
     *
     * @param target 目标方法
     * @return 实际重定向的抛异常点数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectThrowCount(target: MethodNode): Int {
        val normalizedTarget = redirectTarget.replace('.', '/')
        val inferTarget = normalizedTarget.isEmpty()
        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode != Opcodes.ATHROW) {
                continue
            }
            if (!inferTarget && directThrownTypeInternalName(insn) != normalizedTarget) {
                continue
            }
            if (inferTarget && !isThrowHandlerCompatible(target)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            replaceThrow(target, instructions, insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容 `ATHROW` 候选。
     *
     * 该方法用于目标推断模式，签名校验失败的候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @return handler 签名可重定向异常对象时返回 `true`
     */
    private fun isThrowHandlerCompatible(target: MethodNode): Boolean =
        runCatching { validateThrowHandlerSignature(target) }.isSuccess

    /**
     * 重定向字段读取结果。
     *
     * 该入口匹配 `GETFIELD` 或 `GETSTATIC`，并用 handler 调用替换原字段读取。
     *
     * @param target 目标方法
     * @return 实际重定向的字段读取数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldReadCount(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(redirectTarget)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("Invalid target field signature: $redirectTarget")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is FieldInsnNode &&
                insn.opcode in FIELD_READ_OPS &&
                matchesTargetField(insn, fieldTarget)
            ) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceFieldRead(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    /**
     * 重定向字段待写入值。
     *
     * 该入口匹配 `PUTFIELD` 或 `PUTSTATIC`，并用 handler 返回值替代原字段写入值。
     *
     * @param target 目标方法
     * @return 实际重定向的字段写入数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldAssignCount(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(redirectTarget)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("Invalid target field signature: $redirectTarget")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is FieldInsnNode &&
                insn.opcode in FIELD_WRITE_OPS &&
                matchesTargetField(insn, fieldTarget)
            ) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceFieldAssign(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    /**
     * 解析目标方法签名。
     *
     * 支持 owner 使用 slash 或 dot：
     * - java/lang/String.trim()Ljava/lang/String;
     * - java.lang.String.trim()Ljava/lang/String;
     * - trim()Ljava/lang/String;
     *
     * @param signature `At.target` 中声明的方法目标
     * @return owner、方法名与描述符；未声明的部分返回 `null`
     */
    private fun parseTargetMethod(signature: String): Triple<String?, String?, String?> {
        if (signature.isEmpty()) {
            return Triple(null, null, null)
        }

        val parenIndex = signature.indexOf('(')
        if (parenIndex < 0) {
            return Triple(null, signature, null)
        }

        val ownerAndName = signature.substring(0, parenIndex)
        val desc = signature.substring(parenIndex)
        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)

        return if (separatorIndex >= 0) {
            val owner = ownerAndName.substring(0, separatorIndex).replace('.', '/')
            val methodName = ownerAndName.substring(separatorIndex + 1)
            Triple(owner, methodName, desc)
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    /**
     * 判断普通方法调用是否匹配目标方法约束。
     *
     * @param insn 候选普通方法调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 owner
     * @param targetName 目标方法名
     * @param targetDesc 目标方法描述符；为空字符串时不限制描述符
     * @return 候选调用满足目标约束时返回 `true`
     */
    private fun matchesTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName) {
            return false
        }
        return targetDesc.isEmpty() || insn.desc == targetDesc
    }

    /**
     * 判断 `invokedynamic` 调用是否匹配目标方法约束。
     *
     * owner 约束会匹配 bootstrap method owner，名称约束可匹配动态调用名或 bootstrap method 名。
     *
     * @param insn 候选 `invokedynamic` 调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 bootstrap owner
     * @param targetName 目标动态调用名或 bootstrap method 名
     * @param targetDesc 目标动态调用描述符；为空字符串时不限制描述符
     * @return 候选动态调用满足目标约束时返回 `true`
     */
    private fun matchesTargetInvokeDynamic(
        insn: InvokeDynamicInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        if (targetOwner != null && insn.bsm.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName && insn.bsm.name != targetName) {
            return false
        }
        return targetDesc.isEmpty() || insn.desc == targetDesc
    }

    /**
     * 解析字段目标签名。
     *
     * 支持 `owner.name:desc`、`owner/name:desc`、`name:desc` 与仅字段名形式；
     * owner 会统一转换为 JVM internal name，空签名表示不限制字段。
     *
     * @param signature `At.target` 中声明的字段目标
     * @return 解析后的字段目标约束
     */
    private fun parseFieldTarget(signature: String): FieldTarget {
        if (signature.isEmpty()) {
            return FieldTarget(null, null, null)
        }

        val colonIndex = signature.indexOf(':')
        val ownerAndName = if (colonIndex >= 0) signature.substring(0, colonIndex) else signature
        val desc = if (colonIndex >= 0) signature.substring(colonIndex + 1) else null
        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)

        return if (separatorIndex >= 0) {
            FieldTarget(
                owner = ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                name = ownerAndName.substring(separatorIndex + 1),
                desc = desc,
            )
        } else {
            FieldTarget(owner = null, name = ownerAndName, desc = desc)
        }
    }

    /**
     * 判断字段指令是否匹配字段目标约束。
     *
     * @param insn 候选字段指令
     * @param target 字段目标约束
     * @return 候选字段满足 owner、name 与 descriptor 约束时返回 `true`
     */
    private fun matchesTargetField(
        insn: FieldInsnNode,
        target: FieldTarget,
    ): Boolean {
        if (target.owner != null && insn.owner != target.owner) {
            return false
        }
        if (target.name != null && insn.name != target.name) {
            return false
        }
        return target.desc == null || insn.desc == target.desc
    }

    /**
     * 用 handler 调用替换普通方法调用指令。
     *
     * 静态 handler 会沿用当前调用栈参数并追加目标方法参数；实例 handler 会先暂存原 receiver 与参数，
     * 再创建或读取 handler owner 后按签名顺序重新压栈。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的普通方法调用指令
     * @throws IllegalArgumentException handler 签名与原调用或目标方法参数不兼容时抛出
     */
    private fun replaceMethodCall(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: MethodInsnNode,
    ) {
        val targetParamCount = validateHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (originalReturnType == Type.VOID_TYPE && handlerReturnType != Type.VOID_TYPE) {
            il.add(InsnNode(if (handlerReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
        } else if (originalReturnType != handlerReturnType && originalReturnType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    /**
     * 用 handler 调用替换 `invokedynamic` 调用指令。
     *
     * 该方法按动态调用点描述符校验 handler 参数，必要时在 handler 返回引用值后补充 `CHECKCAST`。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的 `invokedynamic` 指令
     * @throws IllegalArgumentException handler 签名与动态调用点或目标方法参数不兼容时抛出
     */
    private fun replaceInvokeDynamicCall(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: InvokeDynamicInsnNode,
    ) {
        val targetParamCount = validateInvokeDynamicHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectInvokeDynamicHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (originalReturnType == Type.VOID_TYPE && handlerReturnType != Type.VOID_TYPE) {
            il.add(InsnNode(if (handlerReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
        } else if (originalReturnType != handlerReturnType && originalReturnType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    /**
     * 用 handler 调用替换普通 `<init>` 构造器调用。
     *
     * 该方法会定位与构造器调用配对的 `NEW` 与 `DUP`，再移除整段原构造流程。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的构造器调用指令
     * @throws IllegalArgumentException 找不到配对构造分配或 handler 签名不兼容时抛出
     */
    private fun replaceConstructorCall(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: MethodInsnNode,
    ) {
        val allocation = findConstructorAllocation(originalInsn)
        val targetParamCount = validateConstructorHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectConstructorHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val constructedType = Type.getObjectType(originalInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (constructedType != handlerReturnType && constructedType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, constructedType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(allocation.newInsn)
        instructions.remove(allocation.dupInsn)
        instructions.remove(originalInsn)
    }

    /**
     * 用 handler 调用替换 [InjectionPoint.NEW] 命中的构造表达式。
     *
     * 与普通构造器调用替换不同，该入口从 `NEW` 指令出发，并要求后续真实指令为 `DUP`。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param newInsn 被匹配的 `NEW` 指令
     * @param originalInsn 与 `NEW` 配对的构造器调用指令
     * @throws IllegalArgumentException `NEW` 后缺少 `DUP` 或 handler 签名不兼容时抛出
     */
    private fun replaceNewConstructorCall(
        target: MethodNode,
        instructions: InsnList,
        newInsn: TypeInsnNode,
        originalInsn: MethodInsnNode,
    ) {
        val dupInsn = nextRealInstruction(newInsn)
        if (dupInsn?.opcode != Opcodes.DUP) {
            throw IllegalArgumentException("Redirect NEW requires NEW followed by DUP for ${newInsn.desc}")
        }
        val targetParamCount = validateConstructorHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectConstructorHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val constructedType = Type.getObjectType(originalInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (constructedType != handlerReturnType && constructedType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, constructedType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(newInsn)
        instructions.remove(dupInsn)
        instructions.remove(originalInsn)
    }

    /**
     * 向指令列表追加静态 handler 调用。
     *
     * 调用前应已按 handler 签名把所有参数压入栈顶。
     *
     * @param il 正在构造的替换指令列表
     */
    private fun addStaticHandlerCall(il: InsnList) {
        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造普通方法调用重定向参数并追加调用。
     *
     * 原调用参数会按栈顺序暂存到临时局部变量，实例调用还会暂存 receiver；
     * 随后压入 handler owner、原调用参数与追加目标方法参数。
     *
     * @param target 目标方法
     * @param originalInsn 被替换的普通方法调用指令
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectHandlerCall(
        target: MethodNode,
        originalInsn: MethodInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val callParamTypes = Type.getArgumentTypes(originalInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex =
            if (originalInsn.opcode == Opcodes.INVOKESTATIC) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造 `invokedynamic` 重定向参数并追加调用。
     *
     * 动态调用点参数会先按栈顺序暂存，再在 handler owner 后按描述符顺序重新压栈。
     *
     * @param target 目标方法
     * @param originalInsn 被替换的 `invokedynamic` 指令
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectInvokeDynamicHandlerCall(
        target: MethodNode,
        originalInsn: InvokeDynamicInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val callParamTypes = Type.getArgumentTypes(originalInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }

        addHandlerOwner(il)
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造构造器重定向参数并追加调用。
     *
     * 构造器参数会按栈顺序暂存，handler 接收这些参数后返回替代构造结果。
     *
     * @param target 目标方法
     * @param originalInsn 被替换的构造器调用指令
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectConstructorHandlerCall(
        target: MethodNode,
        originalInsn: MethodInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val constructorParamTypes = Type.getArgumentTypes(originalInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val argSlots =
            constructorParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in constructorParamTypes.indices.reversed()) {
            storeStackValue(il, constructorParamTypes[index], argSlots[index])
        }

        addHandlerOwner(il)
        for (index in constructorParamTypes.indices) {
            loadFromVariable(il, constructorParamTypes[index], argSlots[index])
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 用 handler 调用替换字段读取指令。
     *
     * handler 返回值会作为原字段读取结果留在栈顶；引用字段类型不完全一致时会补充 `CHECKCAST`。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的字段读取指令
     * @throws IllegalArgumentException handler 签名与字段读取或目标方法参数不兼容时抛出
     */
    private fun replaceFieldRead(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: FieldInsnNode,
    ) {
        val targetParamCount = validateFieldHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectFieldReadHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val fieldType = Type.getType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (fieldType != handlerReturnType && fieldType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, fieldType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    /**
     * 用 handler 调用替换字段写入指令。
     *
     * handler 会接管原字段写入动作，并按签名接收字段 owner、待写入值与目标方法参数；原 `PUTFIELD` 或 `PUTSTATIC` 会被移除。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的字段写入指令
     * @throws IllegalArgumentException handler 签名与字段写入或目标方法参数不兼容时抛出
     */
    private fun replaceFieldAssign(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: FieldInsnNode,
    ) {
        val targetParamCount = validateFieldAssignHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectFieldAssignHandlerCall(target, originalInsn, il, targetParamCount)
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    /**
     * 用 handler 调用替换数组元素访问或数组长度读取指令。
     *
     * 数组读取会在引用元素类型不完全一致时补充 `CHECKCAST`；写入和长度模式由 handler 返回值直接供原位置消费。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param arrayInsn 被替换的数组访问或数组长度指令
     * @param fieldInsn 产生数组引用的字段读取指令
     * @param mode 数组访问模式
     * @throws IllegalArgumentException handler 签名与数组访问或目标方法参数不兼容时抛出
     */
    private fun replaceArrayAccess(
        target: MethodNode,
        instructions: InsnList,
        arrayInsn: AbstractInsnNode,
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
    ) {
        val targetParamCount = validateArrayAccessHandlerSignature(target, fieldInsn, mode)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectArrayAccessHandlerCall(target, fieldInsn, il, targetParamCount, mode)
        }

        if (mode == ArrayAccessMode.GET) {
            val elementType = Type.getType(fieldInsn.desc).elementType
            val handlerReturnType = Type.getReturnType(asmMethod)
            if (elementType != handlerReturnType && elementType.sort >= Type.ARRAY) {
                il.add(TypeInsnNode(Opcodes.CHECKCAST, elementType.internalName))
            }
        }

        instructions.insertBefore(arrayInsn, il)
        instructions.remove(arrayInsn)
    }

    /**
     * 用 handler 调用替换 `CHECKCAST` 指令。
     *
     * handler 接收原待转换引用并返回替代引用值，必要时再执行到原目标类型的 `CHECKCAST`。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的 `CHECKCAST` 指令
     * @throws IllegalArgumentException handler 签名与转换结果或目标方法参数不兼容时抛出
     */
    private fun replaceCast(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: TypeInsnNode,
    ) {
        val castType = Type.getObjectType(originalInsn.desc)
        val targetParamCount = validateCastHandlerSignature(target, castType)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectInstanceofHandlerCall(target, il, targetParamCount)
        }

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (castType != handlerReturnType) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, castType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    /**
     * 用 handler 调用替换 `INSTANCEOF` 指令。
     *
     * handler 接收原待判断引用并返回替代 boolean 判断结果。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的 `INSTANCEOF` 指令
     * @throws IllegalArgumentException handler 签名与类型判断或目标方法参数不兼容时抛出
     */
    private fun replaceInstanceof(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: TypeInsnNode,
    ) {
        val targetParamCount = validateInstanceofHandlerSignature(target)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectInstanceofHandlerCall(target, il, targetParamCount)
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    /**
     * 用 handler 调用替换条件跳转指令。
     *
     * 序列会先计算原跳转条件的 boolean 结果，再让 handler 返回新的跳转决策；
     * handler 返回 `true` 时跳向原标签，返回 `false` 时继续落到下一条指令。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被替换的条件跳转指令
     * @throws IllegalArgumentException handler 签名与跳转结果或目标方法参数不兼容时抛出
     */
    private fun replaceJump(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: JumpInsnNode,
    ) {
        val targetParamCount = validateJumpHandlerSignature(target)
        val originalTrue = LabelNode()
        val afterOriginal = LabelNode()
        val il = InsnList()

        il.add(JumpInsnNode(originalInsn.opcode, originalTrue))
        il.add(InsnNode(Opcodes.ICONST_0))
        il.add(JumpInsnNode(Opcodes.GOTO, afterOriginal))
        il.add(originalTrue)
        il.add(InsnNode(Opcodes.ICONST_1))
        il.add(afterOriginal)

        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectJumpHandlerCall(target, il, targetParamCount)
        }
        il.add(JumpInsnNode(Opcodes.IFNE, originalInsn.label))

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    /**
     * 在 switch 指令前插入 selector 重定向调用。
     *
     * handler 接收原 `Int` selector 并返回新的 selector，原 `tableswitch` 或 `lookupswitch` 继续消费返回值。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被重定向的 switch 指令
     * @throws IllegalArgumentException handler 签名与 switch selector 或目标方法参数不兼容时抛出
     */
    private fun replaceSwitch(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: AbstractInsnNode,
    ) {
        val targetParamCount = validateSwitchHandlerSignature(target)
        val il = InsnList()

        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectSwitchHandlerCall(target, il, targetParamCount)
        }

        instructions.insertBefore(originalInsn, il)
    }

    /**
     * 在 `ATHROW` 前插入异常对象重定向调用。
     *
     * 原异常对象会先暂存，handler 返回的新异常对象会被原 `ATHROW` 继续抛出。
     *
     * @param target 目标方法
     * @param instructions 目标方法指令列表
     * @param originalInsn 被重定向的 `ATHROW` 指令
     * @throws IllegalArgumentException handler 签名与异常对象或目标方法参数不兼容时抛出
     */
    private fun replaceThrow(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: AbstractInsnNode,
    ) {
        val targetParamCount = validateThrowHandlerSignature(target)
        val il = InsnList()
        val throwableType = Type.getType(Throwable::class.java)
        val throwableIndex = nextLocalIndex(target)

        storeStackValue(il, throwableType, throwableIndex)
        addHandlerOwner(il)
        loadFromVariable(il, throwableType, throwableIndex)
        loadTargetMethodParameters(il, target, targetParamCount)
        if (isHandlerStatic()) {
            addStaticHandlerCall(il)
        } else {
            addVirtualHandlerCall(il)
        }

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != throwableType) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, throwableType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
    }

    /**
     * 为实例 handler 构造字段读取重定向参数并追加调用。
     *
     * 实例字段读取会先暂存字段 owner；静态字段读取直接压入 handler owner 与追加目标方法参数。
     *
     * @param target 目标方法
     * @param originalInsn 被替换的字段读取指令
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectFieldReadHandlerCall(
        target: MethodNode,
        originalInsn: FieldInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val receiverIndex =
            if (originalInsn.opcode == Opcodes.GETSTATIC) {
                null
            } else {
                nextLocalIndex(target)
            }

        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造字段写入重定向参数并追加调用。
     *
     * 待写入值会先暂存，实例字段写入还会暂存字段 owner，再按 handler 签名顺序重新压栈。
     *
     * @param target 目标方法
     * @param originalInsn 被替换的字段写入指令
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectFieldAssignHandlerCall(
        target: MethodNode,
        originalInsn: FieldInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val fieldType = Type.getType(originalInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex =
            if (originalInsn.opcode == Opcodes.PUTSTATIC) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val valueIndex = nextTempIndex

        storeStackValue(il, fieldType, valueIndex)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        loadFromVariable(il, fieldType, valueIndex)
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造数组访问重定向参数并追加调用。
     *
     * 该方法按访问模式暂存数组、索引与待写入元素值，再压入 handler owner、数组访问参数与目标方法参数。
     *
     * @param target 目标方法
     * @param fieldInsn 产生数组引用的字段读取指令
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @param mode 数组访问模式
     */
    private fun addObjectArrayAccessHandlerCall(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        il: InsnList,
        targetParamCount: Int,
        mode: ArrayAccessMode,
    ) {
        val arrayType = Type.getType(fieldInsn.desc)
        val elementType = arrayType.elementType
        var nextTempIndex = nextLocalIndex(target)
        val arrayIndex = nextTempIndex.also { nextTempIndex += 1 }
        val indexIndex =
            if (mode == ArrayAccessMode.LENGTH) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val valueIndex =
            if (mode == ArrayAccessMode.SET) {
                nextTempIndex.also { nextTempIndex += elementType.size }
            } else {
                null
            }

        if (valueIndex != null) {
            storeStackValue(il, elementType, valueIndex)
        }
        if (indexIndex != null) {
            storeStackValue(il, Type.INT_TYPE, indexIndex)
        }
        storeStackValue(il, arrayType, arrayIndex)

        addHandlerOwner(il)
        loadFromVariable(il, arrayType, arrayIndex)
        if (indexIndex != null) {
            loadFromVariable(il, Type.INT_TYPE, indexIndex)
        }
        if (valueIndex != null) {
            loadFromVariable(il, elementType, valueIndex)
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造引用值重定向参数并追加调用。
     *
     * `CHECKCAST` 与 `INSTANCEOF` 共用该 helper：原引用值会先暂存，再作为 handler 首参重新压栈。
     *
     * @param target 目标方法
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectInstanceofHandlerCall(
        target: MethodNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val valueIndex = nextLocalIndex(target)

        il.add(VarInsnNode(Opcodes.ASTORE, valueIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, valueIndex))
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造条件跳转重定向参数并追加调用。
     *
     * 原条件结果会暂存为 boolean，再作为 handler 首参传入。
     *
     * @param target 目标方法
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectJumpHandlerCall(
        target: MethodNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val branchIndex = nextLocalIndex(target)

        il.add(VarInsnNode(Opcodes.ISTORE, branchIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ILOAD, branchIndex))
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 handler 构造 switch selector 重定向参数并追加调用。
     *
     * 原 selector 会暂存为 `Int`，再作为 handler 首参传入。
     *
     * @param target 目标方法
     * @param il 正在构造的替换指令列表
     * @param targetParamCount handler 追加接收的目标方法参数数量
     */
    private fun addObjectSwitchHandlerCall(
        target: MethodNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val selectorIndex = nextLocalIndex(target)

        il.add(VarInsnNode(Opcodes.ISTORE, selectorIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ILOAD, selectorIndex))
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 向指令列表追加实例 handler 虚调用。
     *
     * 调用前应已压入 handler owner 与所有 handler 参数。
     *
     * @param il 正在构造的替换指令列表
     */
    private fun addVirtualHandlerCall(il: InsnList) {
        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 构造局部变量值或常量值重定向指令序列。
     *
     * 序列会先暂存原值，调用 handler 取得替代值，并在需要时补充返回值到原值类型的 `CHECKCAST`。
     *
     * @param target 目标方法
     * @param valueType 原局部变量值或常量值类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 可插入到原值消费位置附近的指令列表
     */
    private fun buildLocalValueRedirect(
        target: MethodNode,
        valueType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val valueIndex = nextLocalIndex(target)

        storeStackValue(il, valueType, valueIndex)
        addHandlerOwner(il)
        loadFromVariable(il, valueType, valueIndex)
        loadTargetMethodParameters(il, target, targetParamCount)
        if (isHandlerStatic()) {
            addStaticHandlerCall(il)
        } else {
            addVirtualHandlerCall(il)
        }
        addLocalValueCastIfNeeded(il, valueType)

        return il
    }

    /**
     * 校验普通方法调用重定向的 handler 签名。
     *
     * handler 必须是静态方法、`@JvmStatic` 方法或 Kotlin `object` 实例方法；
     * 参数前缀需要兼容原调用栈参数，额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param originalInsn 被重定向的普通方法调用指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateHandlerSignature(
        target: MethodNode,
        originalInsn: MethodInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedHandlerParams(originalInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }

        val requestedTargetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(originalReturnType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} return type mismatch: original $originalReturnType, handler $handlerReturnType",
            )
        }
        return requestedTargetParamCount
    }

    /**
     * 校验构造器重定向的 handler 签名。
     *
     * handler 前缀参数需要兼容原构造器参数，返回值需要能替代构造出的对象类型；
     * 额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param originalInsn 被重定向的构造器调用指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或构造结果返回值不兼容时抛出
     */
    private fun validateConstructorHandlerSignature(
        target: MethodNode,
        originalInsn: MethodInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = Type.getArgumentTypes(originalInsn.desc)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect constructor handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect constructor handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val constructedType = Type.getObjectType(originalInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isConstructorReturnCompatible(constructedType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect constructor handler ${asmMethod.name} return type mismatch: " +
                    "original $constructedType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验 `invokedynamic` 重定向的 handler 签名。
     *
     * handler 前缀参数需要兼容动态调用点描述符参数，返回值需要能替代动态调用点返回值；
     * 额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param originalInsn 被重定向的 `invokedynamic` 指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateInvokeDynamicHandlerSignature(
        target: MethodNode,
        originalInsn: InvokeDynamicInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = Type.getArgumentTypes(originalInsn.desc)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect invokedynamic handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect invokedynamic handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected stack type $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(originalReturnType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect invokedynamic handler ${asmMethod.name} return type mismatch: " +
                    "original $originalReturnType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验 handler 追加接收的目标方法参数前缀。
     *
     * [stackParamCount] 之后的 handler 参数会按顺序映射到目标方法参数；请求数量不能超过目标方法参数数量。
     *
     * @param target 目标方法
     * @param actualParams handler 实际声明的参数类型
     * @param stackParamCount handler 前缀中已经由原栈值提供的参数数量
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException 追加参数数量过多或类型不兼容时抛出
     */
    private fun validateTargetMethodParameters(
        target: MethodNode,
        actualParams: Array<Type>,
        stackParamCount: Int,
    ): Int {
        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - stackParamCount
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[stackParamCount + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} target parameter #$index mismatch: expected $expected, actual $actual",
                )
            }
        }
        return requestedTargetParamCount
    }

    /**
     * 校验字段读取重定向的 handler 签名。
     *
     * handler 前缀参数需要兼容原字段读取所需的 owner 参数，返回值需要能替代字段值；
     * 额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param originalInsn 被重定向的字段读取指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateFieldHandlerSignature(
        target: MethodNode,
        originalInsn: FieldInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedFieldHandlerParams(originalInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val fieldType = Type.getType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(fieldType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} return type mismatch: original $fieldType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验字段写入重定向的 handler 签名。
     *
     * handler 前缀参数需要兼容字段 owner 与待写入值，返回值必须为 `void`；
     * 额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param originalInsn 被重定向的字段写入指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateFieldAssignHandlerSignature(
        target: MethodNode,
        originalInsn: FieldInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedFieldAssignHandlerParams(originalInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != Type.VOID_TYPE) {
            throw IllegalStateException(
                "Redirect field assign handler ${asmMethod.name} must return void, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验 `CHECKCAST` 重定向的 handler 签名。
     *
     * handler 首参接收原待转换引用，返回值需要能替代原转换结果；
     * 额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param castType 原 `CHECKCAST` 目标类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateCastHandlerSignature(
        target: MethodNode,
        castType: Type,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val expectedParams = arrayOf(Type.getType(Any::class.java))
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect CAST handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(expectedParams[0], actualParams[0])) {
            throw IllegalStateException(
                "Redirect CAST handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type ${expectedParams[0]}, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(castType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect CAST handler ${asmMethod.name} return type mismatch: original $castType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验 `INSTANCEOF` 重定向的 handler 签名。
     *
     * handler 首参接收原待判断引用，并必须返回 `boolean` 作为替代判断结果。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateInstanceofHandlerSignature(target: MethodNode): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val expectedParams = arrayOf(Type.getType(Any::class.java))
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect INSTANCEOF handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(expectedParams[0], actualParams[0])) {
            throw IllegalStateException(
                "Redirect INSTANCEOF handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type ${expectedParams[0]}, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != Type.BOOLEAN_TYPE) {
            throw IllegalStateException(
                "Redirect INSTANCEOF handler ${asmMethod.name} must return boolean, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验条件跳转重定向的 handler 签名。
     *
     * handler 首参接收原跳转条件结果，并必须返回 `boolean` 作为新的跳转决策。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateJumpHandlerSignature(target: MethodNode): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val expectedParams = arrayOf(Type.BOOLEAN_TYPE)
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect JUMP handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(expectedParams[0], actualParams[0])) {
            throw IllegalStateException(
                "Redirect JUMP handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type ${expectedParams[0]}, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != Type.BOOLEAN_TYPE) {
            throw IllegalStateException(
                "Redirect JUMP handler ${asmMethod.name} must return boolean, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验 switch selector 重定向的 handler 签名。
     *
     * handler 首参接收原 `Int` selector，并必须返回新的 `Int` selector。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateSwitchHandlerSignature(target: MethodNode): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val expectedParams = arrayOf(Type.INT_TYPE)
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect SWITCH handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(expectedParams[0], actualParams[0])) {
            throw IllegalStateException(
                "Redirect SWITCH handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type ${expectedParams[0]}, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != Type.INT_TYPE) {
            throw IllegalStateException(
                "Redirect SWITCH handler ${asmMethod.name} must return int, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验异常对象重定向的 handler 签名。
     *
     * handler 首参接收原异常对象，返回值需要能替代 [Throwable] 供原 `ATHROW` 继续抛出。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateThrowHandlerSignature(target: MethodNode): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val throwableType = Type.getType(Throwable::class.java)
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect THROW handler ${asmMethod.name} parameter count mismatch: " +
                    "expected original throwable, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(throwableType, actualParams[0])) {
            throw IllegalStateException(
                "Redirect THROW handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type $throwableType, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(throwableType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect THROW handler ${asmMethod.name} return type mismatch: " +
                    "original $throwableType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验局部变量值或常量值重定向的 handler 签名。
     *
     * handler 首参接收原值，返回值需要能替代该原值；[pointName] 用于区分错误信息中的 LOAD、STORE 或 CONSTANT 场景。
     *
     * @param target 目标方法
     * @param valueType 原局部变量值或常量值类型
     * @param pointName 当前重定向点名称
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateLocalHandlerSignature(
        target: MethodNode,
        valueType: Type,
        pointName: String,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect $pointName handler ${asmMethod.name} parameter count mismatch: " +
                    "expected original local value, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(valueType, actualParams[0])) {
            throw IllegalStateException(
                "Redirect $pointName handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type $valueType, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(valueType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect $pointName handler ${asmMethod.name} return type mismatch: " +
                    "original $valueType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 校验数组访问重定向的 handler 签名。
     *
     * handler 前缀参数按访问模式接收数组、索引与待写入值；读取模式返回元素值，
     * 长度模式返回 `int`，写入模式返回 `void`。
     *
     * @param target 目标方法
     * @param fieldInsn 产生数组引用的字段读取指令
     * @param mode 数组访问模式
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalStateException handler 形态、参数或返回值不兼容时抛出
     */
    private fun validateArrayAccessHandlerSignature(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedArrayAccessHandlerParams(fieldInsn, mode)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (mode == ArrayAccessMode.GET) {
            val elementType = Type.getType(fieldInsn.desc).elementType
            if (!isReturnCompatible(elementType, handlerReturnType)) {
                throw IllegalStateException(
                    "Redirect array read handler ${asmMethod.name} return type mismatch: original $elementType, handler $handlerReturnType",
                )
            }
        } else if (mode == ArrayAccessMode.LENGTH) {
            if (handlerReturnType != Type.INT_TYPE) {
                throw IllegalStateException(
                    "Redirect array length handler ${asmMethod.name} must return int, actual $handlerReturnType",
                )
            }
        } else if (handlerReturnType != Type.VOID_TYPE) {
            throw IllegalStateException(
                "Redirect array write handler ${asmMethod.name} must return void, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    /**
     * 构造普通方法调用 handler 必须接收的前缀参数类型。
     *
     * 实例调用会把调用 owner 作为首参，静态调用只保留原调用参数。
     *
     * @param originalInsn 被重定向的普通方法调用指令
     * @return handler 前缀参数类型数组
     */
    private fun buildExpectedHandlerParams(originalInsn: MethodInsnNode): Array<Type> {
        val originalParams = Type.getArgumentTypes(originalInsn.desc).toList()
        return if (originalInsn.opcode == Opcodes.INVOKESTATIC) {
            originalParams.toTypedArray()
        } else {
            (listOf(Type.getObjectType(originalInsn.owner)) + originalParams).toTypedArray()
        }
    }

    /**
     * 构造字段读取 handler 必须接收的前缀参数类型。
     *
     * 静态字段读取不需要前缀参数，实例字段读取需要字段 owner。
     *
     * @param originalInsn 被重定向的字段读取指令
     * @return handler 前缀参数类型数组
     */
    private fun buildExpectedFieldHandlerParams(originalInsn: FieldInsnNode): Array<Type> =
        if (originalInsn.opcode == Opcodes.GETSTATIC) {
            emptyArray()
        } else {
            arrayOf(Type.getObjectType(originalInsn.owner))
        }

    /**
     * 构造字段写入 handler 必须接收的前缀参数类型。
     *
     * 静态字段写入只需要待写入值，实例字段写入需要字段 owner 与待写入值。
     *
     * @param originalInsn 被重定向的字段写入指令
     * @return handler 前缀参数类型数组
     */
    private fun buildExpectedFieldAssignHandlerParams(originalInsn: FieldInsnNode): Array<Type> {
        val fieldType = Type.getType(originalInsn.desc)
        return if (originalInsn.opcode == Opcodes.PUTSTATIC) {
            arrayOf(fieldType)
        } else {
            arrayOf(Type.getObjectType(originalInsn.owner), fieldType)
        }
    }

    /**
     * 构造数组访问 handler 必须接收的前缀参数类型。
     *
     * 读取模式接收数组和索引，写入模式追加元素值，长度模式只接收数组引用。
     *
     * @param fieldInsn 产生数组引用的字段读取指令
     * @param mode 数组访问模式
     * @return handler 前缀参数类型数组
     */
    private fun buildExpectedArrayAccessHandlerParams(
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
    ): Array<Type> {
        val arrayType = Type.getType(fieldInsn.desc)
        return when (mode) {
            ArrayAccessMode.GET -> arrayOf(arrayType, Type.INT_TYPE)
            ArrayAccessMode.SET -> arrayOf(arrayType, Type.INT_TYPE, arrayType.elementType)
            ArrayAccessMode.LENGTH -> arrayOf(arrayType)
        }
    }

    /**
     * 从数组访问指令向前查找产生数组引用的字段读取指令。
     *
     * 只接受直接邻近且匹配目标字段的字段读取；遇到其他字段指令、方法调用或数组访问时停止，
     * 避免跨过会改变栈结构的复杂表达式。
     *
     * @param arrayInsn 数组元素访问或数组长度指令
     * @param target 字段目标约束
     * @return 匹配的数组字段读取指令；无法确认简单字段数组访问时返回 `null`
     * @throws IllegalArgumentException 匹配字段不是数组类型时抛出
     */
    private fun findArrayFieldProducer(
        arrayInsn: AbstractInsnNode,
        target: FieldTarget,
    ): FieldInsnNode? {
        var cursor = arrayInsn.previous
        while (cursor != null) {
            if (cursor is FieldInsnNode) {
                if (cursor.opcode in FIELD_READ_OPS && matchesTargetField(cursor, target)) {
                    val fieldType = Type.getType(cursor.desc)
                    if (fieldType.sort != Type.ARRAY) {
                        throw IllegalArgumentException(
                            "Redirect array access target must be an array field: ${cursor.owner}.${cursor.name}:${cursor.desc}",
                        )
                    }
                    return cursor
                }
                return null
            }
            if (cursor is MethodInsnNode || cursor.opcode in ARRAY_READ_OPS || cursor.opcode in ARRAY_WRITE_OPS) {
                return null
            }
            cursor = cursor.previous
        }
        return null
    }

    /**
     * 解析局部变量槽位过滤条件。
     *
     * 支持在 `At.args` 中使用 `index=<n>` 或 `var=<n>`，并限制同一个注入点只能声明一个槽位过滤条件。
     *
     * @param pointName 当前重定向点名称，用于错误信息
     * @return 指定的局部变量槽位；未声明过滤条件时返回 `null`
     * @throws IllegalArgumentException 槽位不是整数、为负数或重复声明时抛出
     */
    private fun parseLocalVariableIndex(pointName: String): Int? {
        val values =
            args.mapNotNull { arg ->
                val trimmed = arg.trim()
                when {
                    trimmed.startsWith("index=") -> trimmed.substringAfter("index=")
                    trimmed.startsWith("var=") -> trimmed.substringAfter("var=")
                    else -> null
                }
            }

        if (values.isEmpty()) {
            return null
        }
        require(values.size == 1) {
            "Redirect $pointName supports only one local variable slot filter in At.args"
        }

        val index =
            values.single().toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Redirect $pointName local variable slot filter must be an integer: ${values.single()}",
                )
        require(index >= 0) {
            "Redirect $pointName local variable slot filter must be non-negative: $index"
        }
        return index
    }

    /**
     * 读取局部变量或常量重定向 handler 的原值参数类型。
     *
     * `LOAD`、`STORE` 与 `CONSTANT` handler 的首参必须接收被替换的原始值，
     * 因此这里会在缺少首参时立即失败。
     *
     * @param pointName 当前重定向点名称，用于错误信息
     * @return handler 首参声明的 ASM 类型
     * @throws IllegalArgumentException handler 没有声明原值参数时抛出
     */
    private fun requireLocalHandlerValueType(pointName: String): Type {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty()) {
            throw IllegalArgumentException(
                "Redirect $pointName handler ${asmMethod.name} must take at least one argument for the original local value",
            )
        }
        return handlerParams[0]
    }

    /**
     * 快速判断局部变量 handler 是否可处理指定原值类型。
     *
     * 该检查只校验首参和返回值是否兼容，不校验追加的目标方法参数前缀；
     * 完整签名校验由 [validateLocalHandlerSignature] 执行。
     *
     * @param valueType 候选局部变量值类型
     * @return handler 首参可接收且返回值可替代原值时返回 `true`
     */
    private fun isLocalHandlerCompatible(valueType: Type): Boolean {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty() || !isHandlerParameterCompatible(valueType, handlerParams[0])) {
            return false
        }
        return isReturnCompatible(valueType, Type.getReturnType(asmMethod))
    }

    /**
     * 判断常量重定向 handler 是否可处理指定常量类型。
     *
     * 通过复用完整局部值签名校验并吞掉异常，供候选常量筛选阶段使用。
     *
     * @param target 当前目标方法
     * @param valueType 候选常量值类型
     * @return handler 签名兼容该常量类型时返回 `true`
     */
    private fun isConstantHandlerCompatible(
        target: MethodNode,
        valueType: Type,
    ): Boolean =
        runCatching {
            validateLocalHandlerSignature(target, valueType, "CONSTANT")
        }.isSuccess

    /**
     * 在按槽位过滤 `LOAD` / `STORE` 时尝试推断更精确的引用类型。
     *
     * 基础字节码指令只能区分 `ALOAD` / `ASTORE`，无法携带具体引用类型；
     * 该方法会依次参考方法入口参数、调试局部变量表以及同槽位的相邻使用场景。
     *
     * @param target 当前目标方法
     * @param index 局部变量槽位
     * @param fallbackType handler 首参声明的兜底类型
     * @return 推断出的引用类型；无法安全推断或兜底类型不是引用类型时返回 `null`
     */
    private fun resolveIndexedLocalValueType(
        target: MethodNode,
        index: Int,
        fallbackType: Type,
    ): Type? {
        if (!fallbackType.isReferenceType()) {
            return null
        }

        val headVariable = collectHeadParameters(target).firstOrNull { it.index == index }
        if (headVariable != null) {
            return headVariable.type
        }

        val localVariable =
            target.localVariables
                .filter { it.index == index }
                .mapNotNull { runCatching { Type.getType(it.desc) }.getOrNull() }
                .firstOrNull { it.isReferenceType() && isHandlerParameterCompatible(it, fallbackType) }
        if (localVariable != null) {
            return localVariable
        }

        return referencedTypeFromSlotInstructions(target, index, fallbackType)
    }

    /**
     * 收集目标方法入口参数占用的局部变量槽位。
     *
     * 实例方法会跳过 slot 0 的 `this`，并按 JVM 类型宽度计算 `long` / `double` 的双槽位占用。
     *
     * @param target 当前目标方法
     * @return 方法参数对应的槽位与类型列表
     */
    private fun collectHeadParameters(target: MethodNode): List<LocalSlotType> {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var slot = if (isStatic) 0 else 1
        return buildList {
            for (argumentType in Type.getArgumentTypes(target.desc)) {
                add(LocalSlotType(slot, argumentType))
                slot += argumentType.size
            }
        }
    }

    /**
     * 根据同一槽位的引用读写指令推断局部变量类型。
     *
     * 仅检查 `ALOAD` / `ASTORE`，并要求推断结果能被 handler 首参兼容接收。
     *
     * @param target 当前目标方法
     * @param index 局部变量槽位
     * @param fallbackType handler 首参声明的兜底类型
     * @return 第一个可兼容的推断引用类型；没有可靠线索时返回 `null`
     */
    private fun referencedTypeFromSlotInstructions(
        target: MethodNode,
        index: Int,
        fallbackType: Type,
    ): Type? =
        target.instructions.toArray()
            .asSequence()
            .filterIsInstance<VarInsnNode>()
            .filter { it.`var` == index && it.opcode in SLOT_REFERENCE_OPS }
            .mapNotNull { inferReferenceTypeAroundSlotInstruction(target, it) }
            .firstOrNull { isHandlerParameterCompatible(it, fallbackType) }

    /**
     * 从局部变量读写指令周围的消费或生产语境推断引用类型。
     *
     * `ASTORE` 会优先读取前置 `CHECKCAST` 或字符串常量线索，再向后寻找下一次 `ALOAD` 的消费方；
     * `ALOAD` 则根据后续实例方法、实例字段、`CHECKCAST` 或 `ARETURN` 推断类型。
     *
     * @param target 当前目标方法
     * @param insn 候选 `ALOAD` 或 `ASTORE` 指令
     * @return 推断出的引用类型；语境不足时返回 `null`
     */
    private fun inferReferenceTypeAroundSlotInstruction(
        target: MethodNode,
        insn: VarInsnNode,
    ): Type? {
        if (insn.opcode == Opcodes.ASTORE) {
            val previous = previousRealInstruction(insn)
            if (previous is TypeInsnNode && previous.opcode == Opcodes.CHECKCAST) {
                return Type.getObjectType(previous.desc)
            }
            if (previous is LdcInsnNode && previous.cst is String) {
                return Type.getType(String::class.java)
            }
            inferReferenceTypeFromNextLoadConsumer(target, insn)?.let { return it }
            return null
        }

        val next = nextRealInstruction(insn)
        return when (next) {
            is MethodInsnNode -> {
                val ownerType = Type.getObjectType(next.owner)
                if (next.opcode == Opcodes.INVOKEVIRTUAL || next.opcode == Opcodes.INVOKEINTERFACE) {
                    ownerType
                } else {
                    null
                }
            }
            is FieldInsnNode -> {
                val ownerType = Type.getObjectType(next.owner)
                if (next.opcode == Opcodes.GETFIELD || next.opcode == Opcodes.PUTFIELD) {
                    ownerType
                } else {
                    null
                }
            }
            is TypeInsnNode ->
                if (next.opcode == Opcodes.CHECKCAST) {
                    Type.getObjectType(next.desc)
                } else {
                    null
                }
            else ->
                if (next?.opcode == Opcodes.ARETURN) {
                    val returnType = Type.getReturnType(target.desc)
                    if (returnType.isReferenceType()) returnType else null
                } else {
                    null
                }
        }
    }

    /**
     * 从一次存储后的下一次同槽位读取推断引用类型。
     *
     * 若同槽位在被读取前再次写入，则认为当前存储值已经失效，不再跨写入推断。
     *
     * @param target 当前目标方法
     * @param storeInsn 当前 `ASTORE` 指令
     * @return 下一次同槽位 `ALOAD` 消费方推断出的引用类型；无法确认时返回 `null`
     */
    private fun inferReferenceTypeFromNextLoadConsumer(
        target: MethodNode,
        storeInsn: VarInsnNode,
    ): Type? {
        var cursor = storeInsn.next
        while (cursor != null) {
            if (cursor is VarInsnNode && cursor.`var` == storeInsn.`var`) {
                if (cursor.opcode == Opcodes.ALOAD) {
                    return inferReferenceTypeAroundSlotInstruction(target, cursor)
                }
                if (cursor.opcode in STORE_OPS) {
                    return null
                }
            }
            cursor = cursor.next
        }
        return null
    }

    /**
     * 推断直接抛出的异常构造类型。
     *
     * 仅在 `ATHROW` 前一个真实指令是构造器调用时返回 owner，用于非推断模式下匹配 `THROW` 目标类型。
     *
     * @param throwInsn 候选 `ATHROW` 指令
     * @return 直接构造并抛出的异常 internal name；无法确认时返回 `null`
     */
    private fun directThrownTypeInternalName(throwInsn: AbstractInsnNode): String? {
        val previous = previousRealInstruction(throwInsn)
        if (previous is MethodInsnNode &&
            previous.opcode == Opcodes.INVOKESPECIAL &&
            previous.name == "<init>"
        ) {
            return previous.owner
        }
        return null
    }

    /**
     * 查找构造器调用对应的对象分配指令。
     *
     * 从 `<init>` 调用向前查找同 owner 的 `NEW`，并要求该 `NEW` 后的下一条真实指令为 `DUP`，
     * 以便替换构造表达式时可以同时移除分配与复制指令。
     *
     * @param constructorInsn 被重定向的 `<init>` 调用
     * @return 与构造器调用配对的 `NEW` 与 `DUP`
     * @throws IllegalArgumentException 找不到配对 `NEW` 或 `NEW` 后缺少 `DUP` 时抛出
     */
    private fun findConstructorAllocation(constructorInsn: MethodInsnNode): ConstructorAllocation {
        var cursor = constructorInsn.previous
        while (cursor != null) {
            if (cursor is TypeInsnNode && cursor.opcode == Opcodes.NEW && cursor.desc == constructorInsn.owner) {
                val dupInsn = nextRealInstruction(cursor)
                if (dupInsn?.opcode != Opcodes.DUP) {
                    throw IllegalArgumentException(
                        "Redirect constructor calls require NEW followed by DUP for ${constructorInsn.owner}",
                    )
                }
                return ConstructorAllocation(cursor, dupInsn)
            }
            cursor = cursor.previous
        }

        throw IllegalArgumentException(
            "Redirect cannot find NEW allocation for constructor ${constructorInsn.owner}${constructorInsn.desc}",
        )
    }

    /**
     * 查找 `NEW` 指令后配对的构造器调用。
     *
     * 扫描过程中会统计同 owner 的嵌套 `NEW`，避免把内层构造器调用误配给外层分配。
     *
     * @param newInsn 被 [InjectionPoint.NEW] 命中的 `NEW` 指令
     * @return 与该分配配对的 `<init>` 调用
     * @throws IllegalArgumentException 找不到配对构造器调用时抛出
     */
    private fun findConstructorInvocation(newInsn: TypeInsnNode): MethodInsnNode {
        var nestedSameOwnerNewCount = 0
        var cursor = newInsn.next
        while (cursor != null) {
            if (cursor is TypeInsnNode && cursor.opcode == Opcodes.NEW && cursor.desc == newInsn.desc) {
                nestedSameOwnerNewCount++
            } else if (
                cursor is MethodInsnNode &&
                cursor.opcode == Opcodes.INVOKESPECIAL &&
                cursor.owner == newInsn.desc &&
                cursor.name == "<init>"
            ) {
                if (nestedSameOwnerNewCount == 0) {
                    return cursor
                }
                nestedSameOwnerNewCount--
            }
            cursor = cursor.next
        }

        throw IllegalArgumentException("Redirect cannot find constructor call for NEW ${newInsn.desc}")
    }

    /**
     * 解析条件跳转重定向的 opcode 过滤条件。
     *
     * 目标可声明为 JVM 跳转 opcode 数值或名称；空目标表示不按 opcode 过滤。
     *
     * @param target `At.target` 中声明的跳转 opcode 目标
     * @return 解析出的跳转 opcode；未声明目标时返回 `null`
     * @throws IllegalArgumentException 目标不是受支持的跳转 opcode 时抛出
     */
    private fun parseJumpOpcodeTarget(target: String): Int? {
        if (target.isEmpty()) {
            return null
        }

        val normalized = target.trim().uppercase()
        normalized.toIntOrNull()?.let { opcode ->
            require(opcode in JUMP_OPS) {
                "Redirect JUMP target opcode must be a JVM jump opcode: $target"
            }
            return opcode
        }

        return JUMP_OPCODE_NAMES[normalized]
            ?: throw IllegalArgumentException(
                "Redirect JUMP target must be a jump opcode name or number: $target",
            )
    }

    /**
     * 查找下一条真实字节码指令。
     *
     * 会跳过 label、line number、frame 等 `opcode < 0` 的伪节点。
     *
     * @param insn 起始指令节点
     * @return 下一条真实指令；不存在时返回 `null`
     */
    private fun nextRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var cursor = insn.next
        while (cursor != null && cursor.opcode < 0) {
            cursor = cursor.next
        }
        return cursor
    }

    /**
     * 查找上一条真实字节码指令。
     *
     * 会跳过 label、line number、frame 等 `opcode < 0` 的伪节点。
     *
     * @param insn 起始指令节点
     * @return 上一条真实指令；不存在时返回 `null`
     */
    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var cursor = insn.previous
        while (cursor != null && cursor.opcode < 0) {
            cursor = cursor.previous
        }
        return cursor
    }

    /**
     * 判断 handler 参数类型是否能接收原栈值类型。
     *
     * 基本类型要求完全一致；引用类型允许 handler 声明为更宽的父类型，
     * 其中 `java.lang.Object` 与 `kotlin.Any` 作为通用引用参数特殊放行。
     *
     * @param expected 原栈值类型
     * @param actual handler 声明的参数类型
     * @return handler 参数可安全接收原值时返回 `true`
     */
    private fun isHandlerParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) return true
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    /**
     * 判断 handler 返回值是否能替代原位置期望类型。
     *
     * `void` 只能替代 `void`；基本类型要求完全一致；引用类型允许 handler 返回原类型的子类型，
     * 并兼容 `java.lang.Object` 与 `kotlin.Any` 这类需要后续 `CHECKCAST` 的宽返回类型。
     *
     * @param original 原位置期望的返回或表达式类型
     * @param handler handler 实际返回类型
     * @return handler 返回值可作为原结果使用时返回 `true`
     */
    private fun isReturnCompatible(
        original: Type,
        handler: Type,
    ): Boolean {
        if (original == Type.VOID_TYPE) return handler == Type.VOID_TYPE
        if (handler == Type.VOID_TYPE) return false
        if (original == handler) return true
        if (!original.isReferenceType() || !handler.isReferenceType()) {
            return false
        }
        if (handler.sort == Type.OBJECT &&
            (handler.internalName == "java/lang/Object" || handler.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val originalClass = loadReferenceClass(original)
            originalClass.isAssignableFrom(loadReferenceClass(handler))
        }.getOrDefault(false)
    }

    /**
     * 必要时把局部值重定向 handler 的返回值转回原引用类型。
     *
     * 仅引用类型需要补充 `CHECKCAST`，基本类型已经在签名校验阶段要求精确匹配。
     *
     * @param il 正在构造的替换指令列表
     * @param valueType 原局部变量值或常量值类型
     */
    private fun addLocalValueCastIfNeeded(
        il: InsnList,
        valueType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (valueType != handlerReturnType && valueType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, valueType.internalName))
        }
    }

    /**
     * 判断 ASM 类型是否为 JVM 引用类型。
     *
     * @return 当前类型是对象或数组类型时返回 `true`
     */
    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * 判断局部变量读取指令是否与 handler 首参类型兼容。
     *
     * 该检查基于 load opcode 的栈类型类别，引用读取只要求 handler 首参为对象或数组。
     *
     * @param opcode 候选局部变量读取指令 opcode
     * @param handlerType handler 首参类型
     * @return handler 首参可接收该读取值类别时返回 `true`
     */
    private fun isLoadCompatibleWithHandler(
        opcode: Int,
        handlerType: Type,
    ): Boolean =
        when (opcode) {
            Opcodes.ILOAD -> handlerType.sort in INT_VARIABLE_TYPE_SORTS
            Opcodes.LLOAD -> handlerType == Type.LONG_TYPE
            Opcodes.FLOAD -> handlerType == Type.FLOAT_TYPE
            Opcodes.DLOAD -> handlerType == Type.DOUBLE_TYPE
            Opcodes.ALOAD -> handlerType.sort == Type.OBJECT || handlerType.sort == Type.ARRAY
            else -> false
        }

    /**
     * 判断局部变量写入指令是否与 handler 首参类型兼容。
     *
     * 该检查基于 store opcode 的栈类型类别，引用写入只要求 handler 首参为对象或数组。
     *
     * @param opcode 候选局部变量写入指令 opcode
     * @param handlerType handler 首参类型
     * @return handler 首参可接收该待写入值类别时返回 `true`
     */
    private fun isStoreCompatibleWithHandler(
        opcode: Int,
        handlerType: Type,
    ): Boolean =
        when (opcode) {
            Opcodes.ISTORE -> handlerType.sort in INT_VARIABLE_TYPE_SORTS
            Opcodes.LSTORE -> handlerType == Type.LONG_TYPE
            Opcodes.FSTORE -> handlerType == Type.FLOAT_TYPE
            Opcodes.DSTORE -> handlerType == Type.DOUBLE_TYPE
            Opcodes.ASTORE -> handlerType.sort == Type.OBJECT || handlerType.sort == Type.ARRAY
            else -> false
        }

    /**
     * 按 ASM 引用类型加载对应的运行时 [Class]。
     *
     * 数组类型使用 descriptor 转 Java 类名，对象类型使用 [Type.getClassName]；
     * 类加载器优先取当前 Mixin 类加载器。
     *
     * @param type 待加载的对象或数组类型
     * @return 对应的运行时类
     * @throws ClassNotFoundException 类型无法由当前类加载器解析时抛出
     */
    private fun loadReferenceClass(type: Type): Class<*> {
        val className =
            if (type.sort == Type.ARRAY) {
                type.descriptor.replace('/', '.')
            } else {
                type.className
            }
        val classLoader = asmInfo.asmClass.classLoader ?: ClassLoader.getSystemClassLoader()
        return Class.forName(className, false, classLoader)
    }

    /**
     * 判断构造器重定向 handler 返回值是否能替代构造结果。
     *
     * 当前实现复用普通返回值兼容规则，保留独立入口便于构造器语义后续扩展。
     *
     * @param constructedType 原构造表达式产生的对象类型
     * @param handler handler 实际返回类型
     * @return handler 返回值可作为构造结果使用时返回 `true`
     */
    private fun isConstructorReturnCompatible(
        constructedType: Type,
        handler: Type,
    ): Boolean {
        return isReturnCompatible(constructedType, handler)
    }

    /**
     * 为实例 handler 调用压入 Mixin object 实例。
     *
     * 静态 handler 不需要 owner；实例 handler 当前只支持 Kotlin `object`，因此通过 `INSTANCE` 字段取单例。
     *
     * @param il 正在构造的替换指令列表
     */
    private fun addHandlerOwner(il: InsnList) {
        if (isHandlerStatic()) {
            return
        }

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            FieldInsnNode(
                Opcodes.GETSTATIC,
                instanceType.internalName,
                "INSTANCE",
                "L${instanceType.internalName};",
            ),
        )
    }

    /**
     * 判断当前 handler 是否为 JVM 静态方法。
     *
     * @return handler 带有 `static` 修饰符时返回 `true`
     */
    private fun isHandlerStatic(): Boolean = Modifier.isStatic(asmMethod.modifiers)

    /**
     * 按类型从局部变量槽位加载值。
     *
     * @param il 正在构造的替换指令列表
     * @param paramType 待加载值类型
     * @param varIndex 局部变量槽位
     */
    private fun loadFromVariable(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        InstructionUtil.loadParam(paramType, varIndex).let { il.add(it) }
    }

    /**
     * 将 handler 额外声明的目标方法参数前缀压入栈。
     *
     * 只加载从目标方法参数列表开头起请求的参数数量，并正确处理实例方法的 `this` 槽位与宽类型槽位。
     *
     * @param il 正在构造的替换指令列表
     * @param target 当前目标方法
     * @param requestedTargetParamCount handler 额外请求的目标方法参数数量
     */
    private fun loadTargetMethodParameters(
        il: InsnList,
        target: MethodNode,
        requestedTargetParamCount: Int,
    ) {
        if (requestedTargetParamCount <= 0) {
            return
        }

        var paramVarIndex = if ((target.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        val targetParamTypes = Type.getArgumentTypes(target.desc)
        for (index in 0 until requestedTargetParamCount) {
            val paramType = targetParamTypes[index]
            loadFromVariable(il, paramType, paramVarIndex)
            paramVarIndex += paramType.size
        }
    }

    /**
     * 按类型把栈顶值暂存到局部变量槽位。
     *
     * 根据 ASM 类型选择 `ISTORE`、`LSTORE`、`FSTORE`、`DSTORE` 或 `ASTORE`。
     *
     * @param il 正在构造的替换指令列表
     * @param paramType 栈顶值类型
     * @param varIndex 局部变量槽位
     */
    private fun storeStackValue(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
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
    }

    /**
     * 计算可用于临时变量的下一个局部变量槽位。
     *
     * 结果会覆盖方法参数、调试局部变量表和现有局部变量读写指令已使用的最高槽位。
     *
     * @param target 当前目标方法
     * @return 可安全分配给临时值的起始槽位
     */
    private fun nextLocalIndex(target: MethodNode): Int {
        var maxIndex = if ((target.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        for (paramType in Type.getArgumentTypes(target.desc)) {
            maxIndex += paramType.size
        }
        for (localVar in target.localVariables) {
            maxIndex = maxOf(maxIndex, localVar.index + Type.getType(localVar.desc).size)
        }
        for (insn in target.instructions.toArray()) {
            if (insn is VarInsnNode) {
                val size =
                    when (insn.opcode) {
                        Opcodes.LLOAD, Opcodes.LSTORE, Opcodes.DLOAD, Opcodes.DSTORE -> 2
                        else -> 1
                    }
                maxIndex = maxOf(maxIndex, insn.`var` + size)
            }
        }
        return maxIndex
    }

    private data class FieldTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    private data class ConstructorAllocation(
        val newInsn: TypeInsnNode,
        val dupInsn: AbstractInsnNode,
    )

    private data class LocalSlotType(
        val index: Int,
        val type: Type,
    )

    private enum class ArrayAccessMode {
        GET,
        SET,
        LENGTH,
    }

    private companion object {
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
        private val LOAD_OPS = setOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)
        private val STORE_OPS = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)
        private val SLOT_REFERENCE_OPS = setOf(Opcodes.ALOAD, Opcodes.ASTORE)
        private val INT_VARIABLE_TYPE_SORTS = setOf(Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR)
        private val ARRAY_READ_OPS =
            setOf(
                Opcodes.IALOAD,
                Opcodes.LALOAD,
                Opcodes.FALOAD,
                Opcodes.DALOAD,
                Opcodes.AALOAD,
                Opcodes.BALOAD,
                Opcodes.CALOAD,
                Opcodes.SALOAD,
            )
        private val ARRAY_WRITE_OPS =
            setOf(
                Opcodes.IASTORE,
                Opcodes.LASTORE,
                Opcodes.FASTORE,
                Opcodes.DASTORE,
                Opcodes.AASTORE,
                Opcodes.BASTORE,
                Opcodes.CASTORE,
                Opcodes.SASTORE,
            )
        private val JUMP_OPCODE_NAMES =
            mapOf(
                "IFEQ" to Opcodes.IFEQ,
                "IFNE" to Opcodes.IFNE,
                "IFLT" to Opcodes.IFLT,
                "IFGE" to Opcodes.IFGE,
                "IFGT" to Opcodes.IFGT,
                "IFLE" to Opcodes.IFLE,
                "IF_ICMPEQ" to Opcodes.IF_ICMPEQ,
                "IF_ICMPNE" to Opcodes.IF_ICMPNE,
                "IF_ICMPLT" to Opcodes.IF_ICMPLT,
                "IF_ICMPGE" to Opcodes.IF_ICMPGE,
                "IF_ICMPGT" to Opcodes.IF_ICMPGT,
                "IF_ICMPLE" to Opcodes.IF_ICMPLE,
                "IF_ACMPEQ" to Opcodes.IF_ACMPEQ,
                "IF_ACMPNE" to Opcodes.IF_ACMPNE,
                "GOTO" to Opcodes.GOTO,
                "JSR" to Opcodes.JSR,
                "IFNULL" to Opcodes.IFNULL,
                "IFNONNULL" to Opcodes.IFNONNULL,
            )
        private val JUMP_OPS = JUMP_OPCODE_NAMES.values.toSet()
        private val CONDITIONAL_JUMP_OPS = JUMP_OPS - setOf(Opcodes.GOTO, Opcodes.JSR)
    }
}
