/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.data

import kim.der.asm.api.listener.RedirectionListener
import kim.der.asm.api.replace.RedirectionReplace

/**
 * 需要被重定向/监听的目标方法描述。
 *
 * 该类型用于描述“目标类 + 方法名 + 方法描述符”，并可选绑定：
 *
 * - 用于替换调用的 [RedirectionReplace] 实现类
 * - 用于监听调用的 [RedirectionListener] 实现类
 *
 * @property classPath 目标类内部名（不含前导 `L`，如 `java/lang/String`）
 * @property methodName 目标方法名
 * @property methodParamsInfo 目标方法描述符（如 `(Ljava/lang/String;)V`）
 * @property replaceClass 替换实现类（可选）
 * @property listenerClass 监听实现类（可选）
 * @property desc 统一描述符：`L<classPath>;<methodName><methodParamsInfo>`
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class MethodTypeInfoValue {
    /**
     * 目标类 internal name。
     *
     * 不包含前导 `L` 与结尾 `;`，例如 `java/lang/String`。
     */
    val classPath: String

    /**
     * 目标方法名。
     *
     * 构造方法使用 JVM 名称 `<init>`。
     */
    val methodName: String

    /**
     * 目标方法描述符。
     *
     * 示例：`(Ljava/lang/String;)V`。
     */
    val methodParamsInfo: String

    /**
     * 当前条目是否表示监听器。
     *
     * `true` 表示使用 [listenerClass] 与 [listenerBefore]；`false` 表示使用 [replaceClass]。
     */
    var listenerOrReplace: Boolean = false

    /**
     * 替换实现类。
     *
     * 仅替换条目使用；监听条目为 `null`。
     */
    var replaceClass: Class<out RedirectionReplace>?
        internal set

    /**
     * 监听器是否在原调用前执行。
     *
     * 仅监听条目有语义。
     */
    val listenerBefore: Boolean

    /**
     * 监听实现类。
     *
     * 仅监听条目使用；替换条目为 `null`。
     */
    val listenerClass: Class<out RedirectionListener>?

    /**
     * 统一调用点描述符。
     *
     * 格式为 `L<classPath>;<methodName><methodParamsInfo>`。
     */
    val desc: String get() = "L$classPath;${methodName}$methodParamsInfo"

    /**
     * 创建未绑定替换器或监听器的目标方法描述。
     *
     * @param classPath 目标类 internal name
     * @param methodName 目标方法名
     * @param methodParamsInfo 目标方法描述符
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        classPath: String,
        methodName: String,
        methodParamsInfo: String,
    ) {
        this.classPath = classPath
        this.methodName = methodName
        this.methodParamsInfo = methodParamsInfo
        this.replaceClass = null
        this.listenerBefore = false
        this.listenerClass = null
    }

    /**
     * 创建替换型目标方法描述。
     *
     * @param classPath 目标类 internal name
     * @param methodName 目标方法名
     * @param methodParamsInfo 目标方法描述符
     * @param replaceClass 替换实现类；为 `null` 时仅描述调用点本身
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        classPath: String,
        methodName: String,
        methodParamsInfo: String,
        replaceClass: Class<out RedirectionReplace>? = null,
    ) {
        this.classPath = classPath
        this.methodName = methodName
        this.methodParamsInfo = methodParamsInfo
        this.listenerOrReplace = false
        this.replaceClass = replaceClass
        this.listenerBefore = false
        this.listenerClass = null
    }

    /**
     * 创建监听型目标方法描述。
     *
     * @param classPath 目标类 internal name
     * @param methodName 目标方法名
     * @param methodParamsInfo 目标方法描述符
     * @param before 是否在原调用前执行监听器
     * @param listenerClass 监听实现类；为 `null` 时仅描述调用点本身
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    constructor(
        classPath: String,
        methodName: String,
        methodParamsInfo: String,
        before: Boolean,
        listenerClass: Class<out RedirectionListener>? = null,
    ) {
        this.classPath = classPath
        this.methodName = methodName
        this.methodParamsInfo = methodParamsInfo
        this.listenerOrReplace = true
        this.replaceClass = null
        this.listenerBefore = before
        this.listenerClass = listenerClass
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other is MethodTypeInfoValue) {
            return if (other.listenerOrReplace) {
                classPath == other.classPath &&
                    methodName == other.methodName &&
                    methodParamsInfo == other.methodParamsInfo &&
                    listenerBefore == other.listenerBefore
            } else {
                classPath == other.classPath && methodName == other.methodName && methodParamsInfo == other.methodParamsInfo
            }
        }

        return false
    }

    override fun hashCode(): Int {
        var result = methodName.hashCode()
        result = 31 * result + methodParamsInfo.hashCode()
        return result
    }
}
