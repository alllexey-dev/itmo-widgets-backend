package dev.alllexey.itmowidgets.backend.compat

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Finds `null` where Core declares a non-null Kotlin property. Gson fills fields reflectively, so an
 * absent field or an enum constant the release does not know (1.7.0 maps an unknown
 * `QueueEntryStatus` to `null`) decodes without an error and fails later in the app. Calling each
 * decoded data class's primary constructor with the decoded values runs Kotlin's parameter null
 * checks, which name the property; collections must not hold `null` elements either.
 */
object NonNullCheck {
    private const val CORE_PACKAGE = "dev.alllexey.itmowidgets.core."

    fun violations(value: Any?, path: String = "$"): List<String> = buildList { visit(value, path, this) }

    private fun visit(value: Any?, path: String, out: MutableList<String>) {
        when (value) {
            null -> Unit

            is Collection<*> -> value.forEachIndexed { index, element ->
                if (element == null) out += "$path[$index]: null element" else visit(element, "$path[$index]", out)
            }

            is Map<*, *> -> value.forEach { (key, element) ->
                if (element == null) out += "$path.$key: null value" else visit(element, "$path.$key", out)
            }

            else -> if (value.javaClass.name.startsWith(CORE_PACKAGE) && !value.javaClass.isEnum) {
                visitModel(value, path, out)
            }
        }
    }

    private fun visitModel(value: Any, path: String, out: MutableList<String>) {
        val type = value.javaClass
        val components = components(type)
        if (components.isEmpty()) {
            out += "$path: ${type.name} is not a data class, so its nullability cannot be checked; extend NonNullCheck"
            return
        }
        val values = components.map { it.invoke(value) }
        val componentTypes = components.map(Method::getReturnType)
        val constructor = type.declaredConstructors.singleOrNull { it.parameterTypes.toList() == componentTypes }
        if (constructor == null) {
            out += "$path: ${type.name} has no primary constructor matching its components"
            return
        }
        try {
            constructor.isAccessible = true
            constructor.newInstance(*values.toTypedArray())
        } catch (error: InvocationTargetException) {
            out += "$path: ${type.simpleName} rejects the decoded values: ${error.targetException.message}"
        }
        val names = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }
        values.forEachIndexed { index, component ->
            visit(component, "$path.${names.getOrElse(index) { "component${index + 1}" }}", out)
        }
    }

    private fun components(type: Class<*>): List<Method> = generateSequence(1) { it + 1 }
        .map { number -> type.methods.firstOrNull { it.name == "component$number" && it.parameterCount == 0 } }
        .takeWhile { it != null }
        .filterNotNull()
        .toList()
}
