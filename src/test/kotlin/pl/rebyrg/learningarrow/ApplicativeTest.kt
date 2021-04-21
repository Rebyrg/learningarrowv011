package pl.rebyrg.learningarrow

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import arrow.core.extensions.either.applicative.applicative
import arrow.core.extensions.either.applicative.just
import arrow.core.extensions.either.apply.ap
import arrow.core.left
import arrow.core.right
import arrow.syntax.function.curried
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class ApplicativeTest {

    @Test
    fun `naive usage`() {
        val fa: Either<Unit, String> = Either.applicative<Unit>().just("tEsT")
        val f: (String) -> String = { x -> x.toUpperCase() }
        val ff: Either<Unit, (String) -> String> = Either.applicative<Unit>().just(f)
        val fb: Either<Unit, String> = fa.ap(ff)
        Assertions.assertEquals(fb, Either.applicative<Unit>().just("TEST"))
    }

    @Test
    fun `simple composition`() {
        data class Person(val firstName: String, val lastName: String)
        val firstName: Either<Unit, String> = "Jan".just()
        val lastName: Either<Unit, String> = "Kowalski".just()
        val personConstructor: (String) -> ((String) -> Person) = ::Person.curried()
        val wrappedPersonConstructor: Either<Unit, (String) -> (String) -> Person> = personConstructor.just()
        val partiallyAppliedPersonConstructor: Either<Unit, (String) -> Person> = firstName.ap(wrappedPersonConstructor)
        val person: Either<Unit, Person> = lastName.ap(partiallyAppliedPersonConstructor)
        Assertions.assertEquals(Either.applicative<Unit>().just(Person("Jan", "Kowalski")), person)
    }

    val f1: (Int) -> Either<Unit, String> = { i: Int -> i.toString().right() }

    val f2: (String) -> Either<Unit, Int> = { i: String ->
        try {
            i.toInt().right()
        } catch (e: NumberFormatException) {
            Unit.left()
        }
    }

    val f3: (Int) -> Either<Unit, Boolean> = { i: Int ->
        when (i) {
            in 0..9 -> true.right()
            in 10..99 -> false.right()
            else -> Unit.left()
        }
    }

    data class Value(val text: String, val number: Int, val boolean: Boolean)
    fun values() = Stream.of(
        Arguments.of(1, "1", 1, Right(Value("1", 1, true))),
        Arguments.of(1, "zly numer", 1000, Left(Unit)),
        Arguments.of(1, "1", 1000, Left(Unit))
    )
    @ParameterizedTest
    @MethodSource("values")
    fun `applicative composition`(a: Int, b: String, c: Int, expected: Either<Unit, Value>) {
        val valueCurriedConstructor = ::Value.curried() //(String) -> (Int) -> (Boolean) -> Value
        Assertions.assertEquals(Value("1", 1, true), valueCurriedConstructor("1")( 1)( true))
        val result: Either<Unit, Value> = f3(c) //Either<Unit, Boolean>
            .ap(//Either<Unit, (Boolean) -> Value>
                f2(b) //Either<Unit, Int>
                    .ap(//Either<Unit, (Int) -> (Boolean) -> Value>
                        f1(a) //Either<Unit, String>
                            .ap(//Either<Unit, (String) -> (Int) -> (Boolean) -> Value>
                                valueCurriedConstructor.right()
                            )
                    )
            )
        Assertions.assertEquals(expected, result)
    }

    @ParameterizedTest
    @MethodSource("values")
    fun `applicative builder`(a: Int, b: String, c: Int, expected: Either<Unit, Value>) {
        val result = Either.applicative<Unit>()
            .mapN(f1(a), f2(b) , f3(c)) { (aOut, bOut, cOut) ->
                Value(aOut, bOut, cOut)
            }
        Assertions.assertEquals(expected, result)
    }

}
