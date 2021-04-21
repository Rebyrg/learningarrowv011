package pl.rebyrg.learningarrow

import arrow.core.extensions.id.comonad.extract
import arrow.mtl.Reader
import arrow.mtl.ReaderApi
import arrow.mtl.extensions.fx
import arrow.typeclasses.internal.IdBimonad
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class DependencyInjectionTest {

    class Algebra {
        fun multiplyByConstant(value: Int): Reader<Dependencies, Int> =
            Reader{ d: Dependencies -> d.intConstantFromConfig() * value }

        fun constantByName(value: String): Reader<Dependencies, Int> =
            Reader { it.intConstantFromConfigByName(value) }

        fun combineReaderOperations(value: String): Reader<Dependencies, Int> =
            Reader.fx(IdBimonad) {
                val x = constantByName(value).bind()
                val y = multiplyByConstant(x).bind()
                y
            }
    }

    interface Dependencies{
        fun intConstantFromConfig(): Int
        fun intConstantFromConfigByName(name: String): Int
    }

    @Test
    fun builderTest() {
        val mockDependencies = object : Dependencies {
            override fun intConstantFromConfig(): Int = 13
            override fun intConstantFromConfigByName(name: String): Int =
                mapOf("a" to 1, "b" to 2)[name] ?: 0
        }
        val algebra = Algebra()
        val result = algebra.combineReaderOperations("b").run(mockDependencies).extract()
        Assertions.assertEquals(26, result)
    }

}
