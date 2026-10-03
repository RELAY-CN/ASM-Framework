/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.injector.util

import kim.der.asm.api.annotation.AddField
import kim.der.asm.api.annotation.Shadow
import java.lang.reflect.Field

/** 成员声明校验、字段修饰符和复制指令共用名称解析，防止各路径对同一别名产生不同解释。 */
internal object MixinMemberNames {
    /** Shadow 的参数是名称提示；空值保留声明名，只有显式填写的 shadow_ 前缀才会被去掉。 */
    fun shadowTargetName(declaredName: String, memberName: String): String =
        when {
            declaredName.isEmpty() -> memberName
            declaredName.startsWith(Shadow.prefix) -> declaredName.substring(Shadow.prefix.length)
            else -> declaredName
        }

    /** AddField 决定实际声明名，双注解字段的修饰符与指令都必须遵循同一优先级。 */
    fun fieldTargetName(field: Field): String =
        field.getAnnotation(AddField::class.java)?.let { it.field.ifEmpty { field.name } }
            ?: shadowTargetName(field.getAnnotation(Shadow::class.java)?.method.orEmpty(), field.name)
}
