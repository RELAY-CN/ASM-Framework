/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.injector.util

import kim.der.asm.api.annotation.AddField
import kim.der.asm.api.annotation.Shadow
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList

/**
 * 将复制方法体中的 Mixin 字段引用绑定到目标声明。
 *
 * Overwrite、Copy 和 inline 共用该入口，避免字段已添加到目标类而指令 owner 仍指向 Mixin。
 * 这里只改写符号引用，不迁移初始化表达式，也不改变普通回调的 receiver 语义。
 */
internal object MixinFieldReferences {
    /** 保留字段类型和读写指令，仅为当前 Mixin 的映射字段替换 owner 与名称。 */
    fun remap(instructions: InsnList, mixinClass: Class<*>, targetClassName: String) {
        val targetNames = mutableMapOf<Pair<String, String>, String>()
        for (field in mixinClass.declaredFields) {
            val added = field.getAnnotation(AddField::class.java)
            val shadow = field.getAnnotation(Shadow::class.java)
            // 与 TargetClassContext.applyFields 一致：AddField 决定双注解字段的实际声明名。
            val targetName = when {
                added != null -> added.field.ifEmpty { field.name }
                shadow != null -> when {
                    shadow.method.isEmpty() -> field.name
                    shadow.method.startsWith(Shadow.prefix) -> shadow.method.substring(Shadow.prefix.length)
                    else -> shadow.method
                }
                else -> continue
            }
            targetNames[field.name to Type.getDescriptor(field.type)] = targetName
        }

        val mixinName = Type.getInternalName(mixinClass)
        for (instruction in instructions) {
            if (instruction !is FieldInsnNode || instruction.owner != mixinName) continue
            val targetName = targetNames[instruction.name to instruction.desc] ?: continue
            instruction.owner = targetClassName
            instruction.name = targetName
        }
    }
}
