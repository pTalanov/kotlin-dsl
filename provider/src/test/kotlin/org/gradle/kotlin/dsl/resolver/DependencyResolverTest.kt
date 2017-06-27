package org.gradle.kotlin.dsl.resolver

import org.gradle.kotlin.dsl.tooling.models.KotlinBuildScriptModel
import org.junit.Assert
import java.io.File
import kotlin.script.dependencies.Environment

class DependencyResolverTest {

    @org.junit.Test
    fun `given an environment with a 'getScriptSectionTokens' entry, when no buildscript change, it will not try to retrieve the model`() {

        val environment =
            environmentWithGetScriptSectionTokensReturning(
                "buildscript" to sequenceOf(""),
                "plugins" to sequenceOf(""))

        trackingRequests {
            assertFetchesModel(environment)
            assertDoesNotFetch(environment)
        }
    }

    @org.junit.Test
    fun `given an environment with a 'getScriptSectionTokens' entry, when buildscript changes, it will try to retrieve the model again`() {

        val env1 = environmentWithGetScriptSectionTokensReturning("buildscript" to sequenceOf("foo"))
        val env2 = environmentWithGetScriptSectionTokensReturning("buildscript" to sequenceOf("bar"))

        trackingRequests {
            assertFetchesModel(env1)
            assertFetchesModel(env2)
        }
    }

    @org.junit.Test
    fun `given an environment with a 'getScriptSectionTokens' entry, when plugins block changes, it will try to retrieve the model again`() {

        val env1 = environmentWithGetScriptSectionTokensReturning("plugins" to sequenceOf("foo"))
        val env2 = environmentWithGetScriptSectionTokensReturning("plugins" to sequenceOf("bar"))

        trackingRequests {
            assertFetchesModel(env1)
            assertFetchesModel(env2)
        }
    }

    @org.junit.Test
    fun `given an environment lacking a 'getScriptSectionTokens' entry, it will always try to retrieve the model`() {

        val environment = mapOf("projectRoot" to File(""))

        trackingRequests {
            assertFetchesModel(environment)
            assertFetchesModel(environment)
        }
    }

    private
    class TrackingResolver {
        private val fetcher = TestModelFetcher()
        private val resolver = KotlinBuildScriptDependenciesResolver(fetcher)

        fun assertFetchesModel(environment: Environment) {
            Assert.assertTrue(checkFetches(environment))
        }

        fun assertDoesNotFetch(environment: Environment) {
            Assert.assertFalse(checkFetches(environment))
        }

        private fun checkFetches(environment: Environment): Boolean {
            val before = fetcher.timesModelRequested
            resolver.resolve(EmptyScriptContents, environment)
            val after = fetcher.timesModelRequested
            return when {
                after == before -> false
                after == before + 1 -> true
                else -> error("Fetched ${after - before} times")
            }
        }
    }

    private
    fun trackingRequests(body: TrackingResolver.() -> Unit) = TrackingResolver().body()

    private
    fun environmentWithGetScriptSectionTokensReturning(vararg sections: Pair<String, Sequence<String>>) =
        environmentWithGetScriptSectionTokens { _, section -> sections.find { it.first == section }?.second ?: emptySequence() }

    private
    fun environmentWithGetScriptSectionTokens(function: (CharSequence, String) -> Sequence<String>) =
        mapOf<String, Any?>("getScriptSectionTokens" to function, "projectRoot" to File("root"))
}

private
object EmptyScriptContents : kotlin.script.dependencies.ScriptContents
{
    override val file: java.io.File? = File("root/someScript.build.gradle.kts")
    override val text: CharSequence? = ""
    override val annotations: Iterable<Annotation> = emptyList()
}

private
object EmptyResponse : KotlinBuildScriptModel
{
    override val classPath = listOf<File>()
    override val sourcePath = listOf<File>()
    override val implicitImports = listOf<String>()
}


private
class TestModelFetcher: KotlinBuiltScriptModelFetcher {
    var timesModelRequested = 0

    suspend override fun fetch(
        request: KotlinBuildScriptModelRequest,
        modelBuilderCustomization: ModelBuilderCustomization
    ): KotlinBuildScriptModel = EmptyResponse
        .also { timesModelRequested++ }
}
