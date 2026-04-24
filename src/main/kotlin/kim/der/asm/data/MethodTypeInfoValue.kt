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
    val classPath: String
    val methodName: String
    val methodParamsInfo: String

    var listenerOrReplace: Boolean = false

    var replaceClass: Class<out RedirectionReplace>?
        internal set

    val listenerBefore: Boolean
    val listenerClass: Class<out RedirectionListener>?

    val desc: String get() = "L$classPath;${methodName}$methodParamsInfo"

    constructor(classPath: String, methodName: String, methodParamsInfo: String) {
        this.classPath = classPath
        this.methodName = methodName
        this.methodParamsInfo = methodParamsInfo
        this.replaceClass = null
        this.listenerBefore = false
        this.listenerClass = null
    }

    constructor(classPath: String, methodName: String, methodParamsInfo: String, replaceClass: Class<out RedirectionReplace>? = null) {
        this.classPath = classPath
        this.methodName = methodName
        this.methodParamsInfo = methodParamsInfo
        this.listenerOrReplace = false
        this.replaceClass = replaceClass
        this.listenerBefore = false
        this.listenerClass = null
    }

    constructor(classPath: String, methodName: String, methodParamsInfo: String, before: Boolean, listenerClass: Class<out RedirectionListener>? = null) {
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
