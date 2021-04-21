package pl.rebyrg.learningarrow

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import arrow.core.Tuple2
import arrow.core.computations.either
import arrow.core.extensions.either.applicativeError.applicativeError
import arrow.core.extensions.list.traverse.traverse
import arrow.core.fix
import arrow.core.identity
import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MonadComprehensionTest {

    data class McError(val message: String)

    fun Int.inRange(from: Int, to: Int): Either<McError, Int> =
        when(this) {
            in from..to -> this.right()
            else -> McError("not in range: $from - $to").left()
        }

    fun Int.isOdd(): Either<McError, Int> =
        when (this % 2) {
            1 -> this.right()
            else -> McError("not odd").left()
        }

    fun String.isName(): Either<McError, String> =
        if (Regex("[A-Z][a-z]*").matches(this)) {
            this.right()
        } else {
            McError("not a name").left()
        }

    val notOdd = McError("not odd")
    val notInRange = McError("not in range: 1 - 100")

    fun eitherValues() =
        Stream.of(
            Arguments.of(1, "Janek", Right(Tuple2(11, "Janek"))),
            Arguments.of(2, "Janek", Left(notOdd)),
            Arguments.of(200, "Janek", Left(notInRange)),
            Arguments.of(200, "1 Janek", Left(notInRange))
        )
    @ParameterizedTest
    @MethodSource("eitherValues")
    fun testEitherMonadComprehension(a: Int, b: String, expected: Either<McError, Tuple2<Int, String>>) {
        val result = either.eager<McError, Tuple2<Int, String>> {
            val validatedRange = a.inRange(1, 100).bind()
            val c = validatedRange + 10
            val validatedAOdd = c.isOdd().bind()
            val validatedName = b.isName().bind()
            Tuple2(validatedAOdd, validatedName)
        }
        Assertions.assertEquals(expected, result)
    }

    fun numbers() =
        Stream.of(
            Arguments.of(listOf(1, 13, 19), Right(listOf(2, 14, 20))),
            Arguments.of(listOf(1, 20, 19), Left(notOdd))
        )
    @ParameterizedTest
    @MethodSource("numbers")
    fun `test transform List of Eithers to Either of List`(numbers: List<Int>, expected: Either<McError, List<Int>>) {
        val oddNumbers = numbers.map { it.isOdd() }
        val result: Either<McError, List<Int>> = oddNumbers.traverse(
            Either.applicativeError()
        ) { it.map { it + 1 } }.fix().map { it.fix() }
        Assertions.assertEquals(expected, result)
    }

}
