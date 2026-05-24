/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Array as ReflectArray

/**
 * 原始调用操作句柄。
 *
 * [WrapOperation] handler 会接收该对象，用于按需执行被包裹的原始方法调用、字段读取、字段写入或数组元素读写。
 * 实例方法调用与 `GETFIELD` 读取需要把 receiver 作为第一个参数传给 [call]，后续参数按原方法描述符顺序
 * 传入；`PUTFIELD` 写入需要传入 receiver 与新字段值。静态方法调用只传入原方法参数，`GETSTATIC`
 * 读取不传入参数，`PUTSTATIC` 写入只传入新字段值。数组读取需要传入数组实例与索引，数组写入需要传入
 * 数组实例、索引与新元素值。
 *
 * 该对象当前通过反射执行原始操作，适合普通可反射访问的方法和字段。若目标方法内部抛出异常，反射调用会把
 * 异常包装为 [java.lang.reflect.InvocationTargetException]。
 *
 * @param ownerClass 原操作 owner 类
 * @param name 原操作方法名或字段名
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
     * 执行原始操作。
     *
     * 实例方法调用和 `GETFIELD` 读取的 [args] 第一个元素必须是 receiver，后续元素为原方法参数；`PUTFIELD`
     * 写入的 [args] 必须依次传入 receiver 与新字段值。静态方法调用的 [args] 只包含原方法参数；`GETSTATIC`
     * 读取的 [args] 必须为空，`PUTSTATIC` 写入的 [args] 必须只包含新字段值。数组读取的 [args] 必须依次包含
     * 数组实例与 `Int` 索引，数组写入的 [args] 必须依次包含数组实例、`Int` 索引与新元素值。返回值类型由调用方的
     * handler 签名决定。
     *
     * @param args 原始操作参数；实例操作需包含 receiver
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

        val expectedArgumentCount = parameterTypes.size + if (staticCall) 0 else 1
        require(args.size == expectedArgumentCount) {
            "Operation ${ownerClass.name}.$name$desc expects $expectedArgumentCount argument(s), actual ${args.size}"
        }

        val receiver = if (staticCall) null else args[0]
        val methodArgs =
            if (staticCall) {
                args
            } else {
                args.copyOfRange(1, args.size)
            }
        val method = findMethod(ownerClass)
        method.isAccessible = true
        return method.invoke(receiver, *methodArgs) as T
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

    private fun findMethod(ownerClass: Class<*>): Method =
        try {
            ownerClass.getDeclaredMethod(name, *parameterTypes)
        } catch (_: NoSuchMethodException) {
            ownerClass.getMethod(name, *parameterTypes)
        }

    private fun findField(ownerClass: Class<*>): Field =
        try {
            ownerClass.getDeclaredField(name)
        } catch (_: NoSuchFieldException) {
            ownerClass.getField(name)
        }

    private enum class OperationKind {
        METHOD_CALL,
        FIELD_READ,
        FIELD_WRITE,
        ARRAY_READ,
        ARRAY_WRITE,
    }
}
