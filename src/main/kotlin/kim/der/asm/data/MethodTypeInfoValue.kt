/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.data

import kim.der.asm.api.listener.RedirectionListener
import kim.der.asm.api.replace.RedirectionReplace

/**
 * ASM 中需要修改的目标方法信息和需要指向的 Class 方法
 *
 * @property classPath 需要修改的Class包路径
 * @property methodName 需要修改的方法名
 * @property methodParamsInfo 需要修改的方法返回值
 * @property replaceClass Class<out RedirectionReplace>?
 * @property listenerClass Class<out RedirectionListener>?
 * @property desc String
 *
 * @date 2023/10/28 12:10
 * @author Dr (dr@der.kim)
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
