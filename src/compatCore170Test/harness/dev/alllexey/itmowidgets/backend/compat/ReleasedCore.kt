package dev.alllexey.itmowidgets.backend.compat

import api.myitmo.MyItmo
import com.google.gson.Gson
import dev.alllexey.itmowidgets.core.ItmoWidgetsApi
import dev.alllexey.itmowidgets.core.ItmoWidgetsImpl
import dev.alllexey.itmowidgets.core.model.fcm.impl.FriendshipEventPayload
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportAutoSignLessonsPayload
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportFreeSignLessonsPayload
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/** One Retrofit method of the released Core: what it calls and the type its response decodes into. */
data class CoreRoute(val name: String, val method: String, val path: String, val response: Type, val body: Type?)

/**
 * The released Core on the suite's classpath, used the way an installed app uses it: the Gson of
 * `ItmoWidgetsImpl(MyItmo())` and the generic types of its Retrofit interfaces, which map a
 * fixture id (the Core method name) to the type Retrofit decodes.
 */
object ReleasedCore {
    val gson: Gson = ItmoWidgetsImpl(MyItmo()).gson

    /** The jar Core was loaded from, so a dependency resolution change cannot swap the release unnoticed. */
    val jarName: String = File(ItmoWidgetsImpl::class.java.protectionDomain.codeSource.location.toURI()).name

    /** `ItmoWidgetsModerationApi` appeared in 1.7.0. */
    private val apis: List<Class<*>> = listOfNotNull(
        ItmoWidgetsApi::class.java,
        runCatching { Class.forName("dev.alllexey.itmowidgets.core.ItmoWidgetsModerationApi") }.getOrNull(),
    )

    val routes: Map<String, CoreRoute> = apis.flatMap { api -> api.declaredMethods.mapNotNull(::route) }
        .also { routes -> check(routes.map { it.name }.distinct().size == routes.size) { "Core method names repeat" } }
        .associateBy { it.name }

    /** Request fixtures named after the body type; a bare collection body is named by the route that sends it. */
    private val bareBodies = mapOf("syncSportLessons" to "SportLessonIds")

    val requests: Map<String, Type> = routes.values.filter { it.body != null }
        .groupBy({ bareBodies[it.name] ?: rawClass(it.body!!).simpleName }, { it.body!! })
        .mapValues { (id, types) -> types.distinct().singleOrNull() ?: error("Request $id has body types $types") }

    /** FCM `type` → payload class; the three payloads exist unchanged in every released Core. */
    val fcmPayloads: Map<String, Class<*>> = mapOf(
        FriendshipEventPayload.TYPE to FriendshipEventPayload::class.java,
        SportAutoSignLessonsPayload.TYPE to SportAutoSignLessonsPayload::class.java,
        SportFreeSignLessonsPayload.TYPE to SportFreeSignLessonsPayload::class.java,
    )

    private fun route(method: Method): CoreRoute? {
        val (verb, path) = method.annotations.firstNotNullOfOrNull(::endpoint) ?: return null
        val bodyIndex = method.parameterAnnotations.indexOfFirst { annotations -> annotations.any { it is Body } }
        return CoreRoute(
            name = method.name,
            method = verb,
            path = path,
            response = responseType(method),
            body = if (bodyIndex < 0) null else method.genericParameterTypes[bodyIndex],
        )
    }

    private fun endpoint(annotation: Annotation): Pair<String, String>? = when (annotation) {
        is GET -> "GET" to annotation.value
        is POST -> "POST" to annotation.value
        is PUT -> "PUT" to annotation.value
        is PATCH -> "PATCH" to annotation.value
        is DELETE -> "DELETE" to annotation.value
        is HTTP -> annotation.method to annotation.path
        else -> null
    }

    /** A suspend method's result type is the lower bound of its trailing `Continuation<in T>`, as Retrofit reads it. */
    private fun responseType(method: Method): Type {
        val continuation = method.genericParameterTypes.lastOrNull() as? ParameterizedType
        if (continuation == null || rawClass(continuation).name != "kotlin.coroutines.Continuation") {
            return method.genericReturnType
        }
        val argument = continuation.actualTypeArguments.single()
        return if (argument is WildcardType) argument.lowerBounds.single() else argument
    }

    private fun rawClass(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is ParameterizedType -> rawClass(type.rawType)
        is WildcardType -> rawClass(type.upperBounds.single())
        else -> error("Unsupported body type $type")
    }
}
