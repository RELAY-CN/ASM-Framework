/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Array as ReflectArray

/**
 * 原始调用操作句柄。
 *
 * [WrapOperation] 与 [WrapMethod] handler 会接收该对象，用于按需执行被包裹的原始方法调用、构造器调用、
 * `invokedynamic` 调用、字段读取、字段写入、数组元素读写、数组长度读取、类型转换、类型判断或整方法原始实现。
 * 实例方法调用与 `GETFIELD` 读取需要把 receiver 作为第一个参数传给 [call]，后续参数按原方法描述符顺序
 * 传入；`PUTFIELD` 写入需要传入 receiver 与新字段值。静态方法调用和 `invokedynamic` 调用只传入原调用参数，
 * `GETSTATIC` 读取不传入参数，`PUTSTATIC` 写入只传入新字段值。数组读取需要传入数组实例与索引，数组写入需要传入
 * 数组实例、索引与新元素值，数组长度读取只需传入数组实例。类型转换与类型判断只需传入待处理值。
 * 构造器调用只传入构造器参数，不传入未初始化 receiver。
 * [WrapMethod] 包裹实例目标方法时 receiver 已绑定到 [Operation]，此时 [call] 只传目标方法参数，不额外传 `this`。
 *
 * 该对象当前通过反射执行原始操作，适合普通可反射访问的方法、构造器和字段。若目标方法或构造器内部抛出异常，
 * 反射调用会把异常包装为 [java.lang.reflect.InvocationTargetException]。
 *
 * @param ownerClass 原操作 owner 类
 * @param name 原操作方法名、字段名或动态调用点名称
 * @param desc 原操作方法描述符或字段描述符
 * @param staticCall 是否为静态调用或静态字段操作
 * @param parameterTypes 原调用参数类型，不包含实例调用 receiver
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class Operation<T> private constructor(
    private val ownerClass: Class<*>,
    private val name: String,
    private val desc: String,
    private val staticCall: Boolean,
    private val parameterTypes: Array<Class<*>>,
    private val kind: OperationKind,
    private val boundReceiver: Any? = null,
    private val receiverBound: Boolean = false,
    private val dynamicReturnType: Class<*>? = null,
    private val bootstrapHandle: MethodHandle? = null,
    private val bootstrapArgs: Array<Any?> = emptyArray(),
) {
    /**
     * 创建方法调用操作句柄。
     *
     * @param ownerClass 原调用 owner 类
     * @param name 原调用方法名
     * @param desc 原调用方法描述符
     * @param staticCall 是否为静态调用
     * @param parameterTypes 原调用参数类型，不包含实例调用 receiver
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        ownerClass: Class<*>,
        name: String,
        desc: String,
        staticCall: Boolean,
        parameterTypes: Array<Class<*>>,
    ) : this(ownerClass, name, desc, staticCall, parameterTypes, OperationKind.METHOD_CALL)

    /**
     * 创建已绑定 receiver 的实例方法调用操作句柄。
     *
     * [WrapMethod] 包裹实例方法时使用该构造器，使 handler 调用 [call] 时只需传入目标方法参数，
     * 不需要也不能额外传入 `this` receiver。
     *
     * @param ownerClass 原调用 owner 类
     * @param name 原调用方法名
     * @param desc 原调用方法描述符
     * @param parameterTypes 原调用参数类型
     * @param receiver 已绑定的目标实例
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        ownerClass: Class<*>,
        name: String,
        desc: String,
        parameterTypes: Array<Class<*>>,
        receiver: Any?,
    ) : this(ownerClass, name, desc, false, parameterTypes, OperationKind.METHOD_CALL, receiver, true)

    /**
     * 创建动态调用操作句柄。
     *
     * 该构造器用于 [WrapOperation] 包裹 `invokedynamic` 调用点。handler 调用 [call] 时只传动态调用点描述符中的
     * 参数，不传 receiver；[call] 会通过 [bootstrapHandle] 创建 [CallSite] 并执行其目标方法句柄。
     *
     * @param bootstrapHandle 原 `invokedynamic` 的 bootstrap 方法句柄
     * @param dynamicName 动态调用点名称
     * @param dynamicDesc 动态调用点描述符
     * @param returnType 动态调用返回类型
     * @param parameterTypes 动态调用参数类型
     * @param bootstrapArgs bootstrap 静态参数
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        bootstrapHandle: MethodHandle,
        dynamicName: String,
        dynamicDesc: String,
        returnType: Class<*>,
        parameterTypes: Array<Class<*>>,
        bootstrapArgs: Array<Any?>,
    ) : this(
        MethodHandle::class.java,
        dynamicName,
        dynamicDesc,
        true,
        parameterTypes,
        OperationKind.INVOKE_DYNAMIC,
        dynamicReturnType = returnType,
        bootstrapHandle = bootstrapHandle,
        bootstrapArgs = bootstrapArgs,
    )

    /**
     * 创建构造器调用操作句柄。
     *
     * @param ownerClass 原构造器 owner 类
     * @param desc 原构造器描述符
     * @param parameterTypes 原构造器参数类型
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        ownerClass: Class<*>,
        desc: String,
        parameterTypes: Array<Class<*>>,
    ) : this(ownerClass, "<init>", desc, true, parameterTypes, OperationKind.CONSTRUCTOR_CALL)

    /**
     * 创建字段读取操作句柄。
     *
     * @param ownerClass 原字段 owner 类
     * @param name 原字段名
     * @param desc 原字段描述符
     * @param staticCall 是否为静态字段读取
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        ownerClass: Class<*>,
        name: String,
        desc: String,
        staticCall: Boolean,
    ) : this(ownerClass, name, desc, staticCall, emptyArray(), OperationKind.FIELD_READ)

    /**
     * 创建字段操作句柄。
     *
     * 当 [write] 为 `false` 时，该构造器等价于字段读取句柄；当 [write] 为 `true` 时，[call] 会执行原字段写入。
     *
     * @param ownerClass 原字段 owner 类
     * @param name 原字段名
     * @param desc 原字段描述符
     * @param staticCall 是否为静态字段操作
     * @param write 是否为字段写入操作
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        ownerClass: Class<*>,
        name: String,
        desc: String,
        staticCall: Boolean,
        write: Boolean,
    ) : this(
        ownerClass,
        name,
        desc,
        staticCall,
        emptyArray(),
        if (write) OperationKind.FIELD_WRITE else OperationKind.FIELD_READ,
    )

    /**
     * 创建数组元素操作句柄。
     *
     * 当 [write] 为 `false` 时，[call] 会读取数组元素；当 [write] 为 `true` 时，[call] 会写入数组元素。
     * 读取参数必须为 `array, index`，写入参数必须为 `array, index, value`。
     *
     * @param arrayClass 原数组运行时类型
     * @param write 是否为数组元素写入操作
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        arrayClass: Class<*>,
        write: Boolean,
    ) : this(
        arrayClass,
        "<array>",
        arrayClass.name,
        false,
        emptyArray(),
        if (write) OperationKind.ARRAY_WRITE else OperationKind.ARRAY_READ,
    )

    /**
     * 创建数组长度操作句柄。
     *
     * [call] 参数必须为 `array`，返回数组长度。
     *
     * @param arrayClass 原数组运行时类型
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        arrayClass: Class<*>,
    ) : this(
        arrayClass,
        "<arraylength>",
        arrayClass.name,
        false,
        emptyArray(),
        OperationKind.ARRAY_LENGTH,
    )

    /**
     * 创建类型检查操作句柄。
     *
     * [marker] 为 `"<checkcast>"` 时，[call] 返回通过原始 `CHECKCAST` 目标类型校验后的值；
     * [marker] 为 `"<instanceof>"` 时，[call] 返回原始 `INSTANCEOF` 类型判断结果。
     *
     * @param targetClass 原类型检查目标类
     * @param marker 类型检查标记；当前支持 `"<checkcast>"` 与 `"<instanceof>"`
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        targetClass: Class<*>,
        marker: String,
    ) : this(
        targetClass,
        marker,
        targetClass.name,
        false,
        emptyArray(),
        markerOperationKind(marker),
    )

    /**
     * 执行原始操作。
     *
     * 普通实例方法调用和 `GETFIELD` 读取的 [args] 第一个元素必须是 receiver，后续元素为原方法参数；`PUTFIELD`
     * 写入的 [args] 必须依次传入 receiver 与新字段值。[WrapMethod] 包裹实例目标方法时 receiver 已绑定，
     * [args] 只包含目标方法参数。静态方法调用和 `invokedynamic` 调用的 [args] 只包含原调用参数；`GETSTATIC`
     * 读取的 [args] 必须为空，`PUTSTATIC` 写入的 [args] 必须只包含新字段值。构造器调用的 [args] 只包含
     * 构造器参数。数组读取的 [args] 必须依次包含数组实例与 `Int` 索引，数组写入的 [args] 必须依次包含
     * 数组实例、`Int` 索引与新元素值，数组长度读取的 [args] 必须只包含数组实例。类型转换与类型判断的
     * [args] 必须只包含待处理值。返回值类型由调用方的 handler 签名决定。
     *
     * @param args 原始操作参数；普通实例操作需包含 receiver，已绑定 receiver 的整方法包裹只传目标方法参数
     * @return 原始操作返回值
     * @throws IllegalArgumentException 参数数量不符合原操作形态时抛出
     * @throws ReflectiveOperationException 目标方法或字段无法解析，或调用/读取失败时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @Suppress("UNCHECKED_CAST")
    fun call(vararg args: Any?): T {
        if (kind == OperationKind.FIELD_READ) {
            return readField(args) as T
        }
        if (kind == OperationKind.FIELD_WRITE) {
            return writeField(args) as T
        }
        if (kind == OperationKind.ARRAY_READ) {
            return readArray(args) as T
        }
        if (kind == OperationKind.ARRAY_WRITE) {
            return writeArray(args) as T
        }
        if (kind == OperationKind.ARRAY_LENGTH) {
            return readArrayLength(args) as T
        }
        if (kind == OperationKind.CAST) {
            return cast(args) as T
        }
        if (kind == OperationKind.INSTANCEOF) {
            return instanceOf(args) as T
        }
        if (kind == OperationKind.CONSTRUCTOR_CALL) {
            return construct(args) as T
        }
        if (kind == OperationKind.INVOKE_DYNAMIC) {
            return invokeDynamic(args) as T
        }

        val usesBoundReceiver = receiverBound && !staticCall
        val expectedArgumentCount = parameterTypes.size + if (staticCall || usesBoundReceiver) 0 else 1
        require(args.size == expectedArgumentCount) {
            "Operation ${ownerClass.name}.$name$desc expects $expectedArgumentCount argument(s), actual ${args.size}"
        }

        val receiver =
            when {
                staticCall -> null
                usesBoundReceiver -> boundReceiver
                else -> args[0]
            }
        val methodArgs =
            if (staticCall || usesBoundReceiver) {
                args
            } else {
                args.copyOfRange(1, args.size)
            }
        val method = findMethod(ownerClass)
        method.isAccessible = true
        return method.invoke(receiver, *methodArgs) as T
    }

    private fun invokeDynamic(args: Array<out Any?>): Any? {
        require(args.size == parameterTypes.size) {
            "Operation invokedynamic $name$desc expects ${parameterTypes.size} argument(s), actual ${args.size}"
        }

        val returnType =
            requireNotNull(dynamicReturnType) {
                "Operation invokedynamic $name$desc has no return type"
            }
        val methodType = MethodType.methodType(returnType, parameterTypes.toList())
        val callSite =
            requireNotNull(bootstrapHandle) {
                "Operation invokedynamic $name$desc has no bootstrap handle"
            }.invokeWithArguments(
                MethodHandles.lookup(),
                name,
                methodType,
                *bootstrapArgs,
            ) as CallSite

        return callSite.dynamicInvoker().invokeWithArguments(*args)
    }

    private fun readField(args: Array<out Any?>): Any? {
        val expectedArgumentCount = if (staticCall) 0 else 1
        require(args.size == expectedArgumentCount) {
            "Operation ${ownerClass.name}.$name:$desc expects $expectedArgumentCount argument(s), actual ${args.size}"
        }

        val field = findField(ownerClass)
        field.isAccessible = true
        return field.get(if (staticCall) null else args[0])
    }

    private fun writeField(args: Array<out Any?>): Any {
        val expectedArgumentCount = if (staticCall) 1 else 2
        require(args.size == expectedArgumentCount) {
            "Operation ${ownerClass.name}.$name:$desc expects $expectedArgumentCount argument(s), actual ${args.size}"
        }

        val field = findField(ownerClass)
        field.isAccessible = true
        if (staticCall) {
            field.set(null, args[0])
        } else {
            field.set(args[0], args[1])
        }
        return Unit
    }

    private fun readArray(args: Array<out Any?>): Any? {
        require(args.size == 2) {
            "Operation ${ownerClass.name}.$name:$desc expects 2 argument(s), actual ${args.size}"
        }

        val index =
            args[1] as? Int
                ?: throw IllegalArgumentException("Operation ${ownerClass.name}.$name:$desc requires Int array index")
        return ReflectArray.get(args[0], index)
    }

    private fun writeArray(args: Array<out Any?>): Any {
        require(args.size == 3) {
            "Operation ${ownerClass.name}.$name:$desc expects 3 argument(s), actual ${args.size}"
        }

        val index =
            args[1] as? Int
                ?: throw IllegalArgumentException("Operation ${ownerClass.name}.$name:$desc requires Int array index")
        ReflectArray.set(args[0], index, args[2])
        return Unit
    }

    private fun readArrayLength(args: Array<out Any?>): Int {
        require(args.size == 1) {
            "Operation ${ownerClass.name}.$name:$desc expects 1 argument(s), actual ${args.size}"
        }

        return ReflectArray.getLength(args[0])
    }

    private fun cast(args: Array<out Any?>): Any? {
        require(args.size == 1) {
            "Operation ${ownerClass.name}.$name:$desc expects 1 argument(s), actual ${args.size}"
        }

        return ownerClass.cast(args[0])
    }

    private fun instanceOf(args: Array<out Any?>): Boolean {
        require(args.size == 1) {
            "Operation ${ownerClass.name}.$name:$desc expects 1 argument(s), actual ${args.size}"
        }

        return ownerClass.isInstance(args[0])
    }

    private fun construct(args: Array<out Any?>): Any {
        require(args.size == parameterTypes.size) {
            "Operation ${ownerClass.name}$desc expects ${parameterTypes.size} argument(s), actual ${args.size}"
        }

        val constructor = findConstructor(ownerClass)
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    private fun findMethod(ownerClass: Class<*>): Method =
        try {
            ownerClass.getDeclaredMethod(name, *parameterTypes)
        } catch (_: NoSuchMethodException) {
            ownerClass.getMethod(name, *parameterTypes)
        }

    private fun findConstructor(ownerClass: Class<*>): Constructor<*> =
        try {
            ownerClass.getDeclaredConstructor(*parameterTypes)
        } catch (_: NoSuchMethodException) {
            ownerClass.getConstructor(*parameterTypes)
        }

    private fun findField(ownerClass: Class<*>): Field =
        try {
            ownerClass.getDeclaredField(name)
        } catch (_: NoSuchFieldException) {
            ownerClass.getField(name)
        }

    private enum class OperationKind {
        METHOD_CALL,
        CONSTRUCTOR_CALL,
        FIELD_READ,
        FIELD_WRITE,
        ARRAY_READ,
        ARRAY_WRITE,
        ARRAY_LENGTH,
        CAST,
        INSTANCEOF,
        INVOKE_DYNAMIC,
    }

    private companion object {
        fun markerOperationKind(marker: String): OperationKind =
            when (marker) {
                "<checkcast>" -> OperationKind.CAST
                "<instanceof>" -> OperationKind.INSTANCEOF
                else -> throw IllegalArgumentException("Unsupported Operation marker: $marker")
            }
    }
}
