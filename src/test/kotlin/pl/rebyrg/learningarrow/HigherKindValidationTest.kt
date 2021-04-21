package pl.rebyrg.learningarrow

import arrow.Kind
import arrow.core.Either
import arrow.core.Invalid
import arrow.core.Left
import arrow.core.Nel
import arrow.core.Right
import arrow.core.Tuple2
import arrow.core.Valid
import arrow.core.Validated
import arrow.core.ValidatedNel
import arrow.core.computations.either
import arrow.core.extensions.either.applicativeError.applicativeError
import arrow.core.extensions.nonemptylist.semigroup.semigroup
import arrow.core.extensions.validated.applicativeError.applicativeError
import arrow.core.nel
import arrow.typeclasses.ApplicativeError
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class HigherKindValidationTest {

    data class HkError(val message: String)

    fun <F> Int.inRange(a: ApplicativeError<F, Nel<HkError>>, from: Int, to: Int): Kind<F, Int> =
        when(this) {
            in from..to -> a.just(this)
            else -> a.raiseError(HkError("not in range: $from - $to").nel())
        }

    fun <F> Int.isOdd(a: ApplicativeError<F, Nel<HkError>>): Kind<F, Int> =
        when (this % 2) {
            1 -> a.just(this)
            else -> a.raiseError(HkError("not odd").nel())
        }

    fun <F> String.isName(a: ApplicativeError<F, Nel<HkError>>): Kind<F, String> =
        if (Regex("[A-Z][a-z]*").matches(this)) {
            a.just(this)
        } else {
            a.raiseError(HkError("not a name").nel())
        }

    val notOdd = HkError("not odd")
    val notInRange = HkError("not in range: 1 - 100")
    val notAName = HkError("not a name")

    fun validatedValues() =
        Stream.of(
            Arguments.of(1, "Janek", Valid(Tuple2(1, "Janek"))),
            Arguments.of(2, "Janek", Invalid(notOdd.nel())),
            Arguments.of(200, "Janek", Invalid(Nel(notInRange, notOdd))),
            Arguments.of(200, "1 Janek", Invalid(Nel(notInRange, notOdd, notAName)))
        )
    @ParameterizedTest
    @MethodSource("validatedValues")
    fun testValidated(a: Int, b: String, expected: ValidatedNel<HkError, Tuple2<Int, String>>) {
        val accumulateErrors = Validated.applicativeError(Nel.semigroup<HkError>())
        val result = accumulateErrors.mapN(
            accumulateErrors.mapN(
                a.inRange(accumulateErrors,1, 100),
                a.isOdd(accumulateErrors)
            ) { it.a },
            b.isName(accumulateErrors)
        ) { it }
        Assertions.assertEquals(expected, result)
    }

    fun eitherValues() =
        Stream.of(
            Arguments.of(1, "Janek", Right(Tuple2(1, "Janek"))),
            Arguments.of(2, "Janek", Left(notOdd.nel())),
            Arguments.of(200, "Janek", Left(notInRange.nel())),
            Arguments.of(200, "1 Janek", Left(notInRange.nel()))
        )
    @ParameterizedTest
    @MethodSource("eitherValues")
    fun testEither(a: Int, b: String, expected: Either<Nel<HkError>, Tuple2<Int, String>>) {
        val failFast = Either.applicativeError<Nel<HkError>>()
        val result = failFast.mapN(
            failFast.mapN(
                a.inRange(failFast,1, 100),
                a.isOdd(failFast)
            ) { it.a },
            b.isName(failFast)
        ) { it }
        Assertions.assertEquals(expected, result)
    }

    @ParameterizedTest
    @MethodSource("eitherValues")
    fun testEitherMonadComprehension(a: Int, b: String, expected: Either<Nel<HkError>, Tuple2<Int, String>>) {
        val failFast = Either.applicativeError<Nel<HkError>>()
        val result = either.eager<Nel<HkError>, Tuple2<Int, String>> {
            val validatedRange = a.inRange(failFast, 1, 100).bind()
            val validatedAOdd = validatedRange.isOdd(failFast).bind()
            val validatedName = b.isName(failFast).bind()
            Tuple2(validatedAOdd, validatedName)
        }
        Assertions.assertEquals(expected, result)
    }

}
