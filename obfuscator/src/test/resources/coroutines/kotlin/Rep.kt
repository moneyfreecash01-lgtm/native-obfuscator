import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Faithful stand-in for the app's suspend functions:
 * a suspend fun that parks the coroutine on Dispatchers.IO, exactly like
 * SetupGates.snapshot / CoverCache.load / GamesScreenKt.refreshGates.
 */
object Rep {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            println("one=" + one())
            println("two=" + two())
            println("three=" + three())
        }
        println("done")
    }

    suspend fun one(): Int = withContext(Dispatchers.IO) { 41 } + 1

    suspend fun two(): String {
        val inner = withContext(Dispatchers.IO) { "x" }
        return "two:" + inner
    }

    // suspend fun calling another suspend fun that itself suspends
    suspend fun three(): String {
        val a = two()
        val b = withContext(Dispatchers.IO) { "y" }
        return "three:" + a + "/" + b
    }
}
