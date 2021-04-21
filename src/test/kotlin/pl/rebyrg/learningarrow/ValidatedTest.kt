package pl.rebyrg.learningarrow

import arrow.core.Invalid
import arrow.core.Nel
import arrow.core.Tuple2
import arrow.core.Valid
import arrow.core.ValidatedNel
import arrow.core.extensions.nonemptylist.semigroup.semigroup
import arrow.core.extensions.validated.applicativeError.applicativeError
import arrow.core.invalidNel
import arrow.core.nel
import arrow.core.valid
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

sealed class ValidationError(open val message: String)
data class NotInRange<T>(override val message: String, val min: T, val max: T): ValidationError(message)
data class BadValue(override val message: String): ValidationError(message)

class ValidatedTest {

    fun Int.inRange(from: Int, to: Int): ValidatedNel<ValidationError, Int> =
        when(this) {
            in from..to -> this.valid()
            else -> NotInRange("not in range", from, to).invalidNel()
        }

    fun Int.isOdd(): ValidatedNel<ValidationError, Int> =
        when (this % 2) {
            1 -> this.valid()
            else -> BadValue("not odd").invalidNel()
        }

    fun String.isName(): ValidatedNel<ValidationError, String> =
        if (Regex("[A-Z][a-z]*").matches(this)) {
            this.valid()
        } else {
            BadValue("not a name").invalidNel()
        }

    fun values() =
        Stream.of(
            Arguments.of(1, "Janek", Valid(Tuple2(1, "Janek"))),
            Arguments.of(2, "Janek", Invalid(BadValue("not odd").nel())),
            Arguments.of(200, "Janek", Invalid(Nel(NotInRange("not in range", 1, 100), BadValue("not odd")))),
            Arguments.of(200, "1 Janek", Invalid(Nel(NotInRange("not in range", 1, 100), BadValue("not odd"), BadValue("not a name"))))
        )
    @ParameterizedTest
    @MethodSource("values")
    fun test(a: Int, b: String, expected: ValidatedNel<ValidationError, Tuple2<Int, String>>) {
        val accumulateErrors = ValidatedNel.applicativeError(Nel.semigroup<ValidationError>())
        val result = accumulateErrors.mapN(
            accumulateErrors.mapN(
                a.inRange(1, 100),
                a.isOdd()
            ) { it.a },
            b.isName()
        ) { it }
        Assertions.assertEquals(expected, result)
    }

}
