package pl.rebyrg.learningarrow

import arrow.core.Either
import arrow.core.EitherPartialOf
import arrow.core.extensions.either.applicative.applicative
import arrow.core.extensions.either.functor.functor
import arrow.core.extensions.either.monad.monad
import arrow.core.extensions.either.monadError.monadError
import arrow.core.fix
import arrow.mtl.Reader
import arrow.mtl.ReaderT
import arrow.mtl.ReaderTOf
import arrow.mtl.ReaderTPartialOf
import arrow.mtl.extensions.kleisli.monad.monad
import arrow.mtl.fix
import arrow.typeclasses.MonadSyntax
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

typealias RE<D, E, A> = ReaderT<D, EitherPartialOf<E>, A>

object REApi {
    fun <D, E> raiseError(e: E): RE<D, E, Nothing> = ReaderT.raiseError(Either.monadError(), e)
    fun <D, A> just(a: A): RE<D, Nothing, A> = ReaderT.just(Either.applicative(), a)
    fun <D, E> ask(): RE<D, E, D> = ReaderT.ask(Either.monad())
    fun <D, E, A> fx(c: suspend MonadSyntax<ReaderTPartialOf<D, EitherPartialOf<E>>>.() -> A): RE<D, E, A> =
        ReaderT.monad<D, EitherPartialOf<E>>(Either.monad()).fx.monad(c).fix()
    fun <D, E, A> fromEither(either: Either<E, A>): RE<D, E, A> =
        Reader.liftF(either)
}

fun <D, E, A, B> RE<D, E, A>.map(f: (A) -> B): RE<D, E, B> =
    map(Either.functor(), f)
fun <D, E, A, B> RE<D, E, A>.flatMap(f: (A) -> ReaderTOf<D, EitherPartialOf<E>, B>): RE<D, E, B> =
    flatMap(Either.monad(), f)
fun <D, E, A> RE<D, E, A>.toEither(d: D): Either<E, A> =
    run(d).fix()

class MonadTransformerTest {

    sealed class DomainError {
        data class Unexpected<C>(val cause: C): DomainError()
        data class Forbidden(val message: String): DomainError()
        data class Wrapped<C>(val cause: C): DomainError()
    }

    interface ADependencies{
        fun foo(): Int
        fun bar(name: String): Int?
    }

    class ADomain {
        fun Int.foo(): RE<ADependencies, DomainError, Int> =
            REApi.ask<ADependencies, DomainError>().map {
                it.foo() * this
            }

        fun bar(name: String): RE<ADependencies, DomainError, Int> =
            REApi.ask<ADependencies, DomainError>().flatMap {
                d -> d.bar(name)
                    ?.let { REApi.just(it) }
                    ?: REApi.raiseError<ADependencies, DomainError>(DomainError.Wrapped("constant not available: $name"))
            }

        fun fooBar(value: String): RE<ADependencies, DomainError, Int> =
            //Kleisli.Companion.fx(Either.monadError()) {
            REApi.fx {
                val bar = bar(value).bind()
                val foo = bar.foo().bind()
                foo
            }
    }

    data class BEntity(val id: Int, val data: Int) {
        fun double() = copy(data = data * 2)
        fun add(value: Int) = copy(data = data + value)
    }

    interface BRepository {
        fun findBiId(id: Int): BEntity?
    }

    class BDomain {
        fun double(id: Int): RE<BRepository, DomainError, BEntity> =
            modify(id) { double() }

        fun add(id: Int, value: Int): RE<BRepository, DomainError, BEntity> =
            modify(id) { add(value) }

        private fun modify(id: Int, operation: BEntity.() -> BEntity): RE<BRepository, DomainError, BEntity> =
            REApi.ask<BRepository, DomainError>().flatMap{
                    repo ->
                repo.findBiId(id)
                    ?.let { entity ->
                        REApi.just(entity.operation())
                    }
                    ?: REApi.raiseError<BRepository, DomainError>(DomainError.Wrapped("can not find $id"))
            }
    }

    data class AppDependencies(val a: ADependencies, val b: BRepository)

    interface Storage{
        fun save(entity: BEntity)
    }

    data class Application(val ctx: AppDependencies, val storage: Storage) {
        val aDomain = ADomain()
        val bDomain = BDomain()

        fun appX(barName: String) {
            val result: RE<AppDependencies, DomainError, BEntity> = REApi.fx {  //Kleisli.Companion.fx(Either.monadError()) {
                val fooBar = aDomain.fooBar(barName).local<AppDependencies> { it.a }.bind()
                val entity = bDomain.double(fooBar).local<AppDependencies> { it.b }.bind()
                entity
            }
            result.run(ctx).fix().map { storage.save(it) }
        }
    }

    @Test
    fun `should change and save entity`() {
        //given
        val aDependencies: ADependencies = mockk()
        val bRepository: BRepository = mockk()
        val storage: Storage = mockk()
        every { aDependencies.foo() } returns 13
        every { aDependencies.bar("b") } returns 2
        every { bRepository.findBiId(any()) } returns BEntity(26, 5)
        every { storage.save(any()) } returns Unit
        val ctx = AppDependencies(aDependencies, bRepository)
        val app = Application(ctx, storage)
        val expectedEntity = BEntity(26, 10)
        //when
        app.appX("b")
        //then
        verify(exactly = 1) {
            aDependencies.foo()
            aDependencies.bar("b")
            bRepository.findBiId(26)
            storage.save(expectedEntity)
        }
    }

    data class ApplicationEitherREMixing(val ctx: AppDependencies, val storage: Storage) {
        private val aDomain = ADomain()
        private val bDomain = BDomain()

        private fun div(dividend: Int, divider: Int): Either<DomainError, Int> =
            Either.conditionally(divider != 0,
                { DomainError.Wrapped("division by zero") },
                { dividend / divider }
            )

        private fun fpLogic(barName: String): Either<DomainError, BEntity> {
            val result: RE<AppDependencies, DomainError, BEntity> = REApi.fx {  //Kleisli.Companion.fx(Either.monadError()) {
                val fooBar = aDomain.fooBar(barName).local<AppDependencies> { it.a }.bind()
                val fooBarDividedBy2 = REApi.fromEither<AppDependencies, DomainError, Int>(div(fooBar, 2)).bind()
                val entity = bDomain.double(fooBarDividedBy2).local<AppDependencies> { it.b }.bind()
                entity
            }
            return result.toEither(ctx)
        }

        fun appX(barName: String) {
            fpLogic(barName).map { storage.save(it) }
        }
    }

    @Test
    fun `should change and save entity mixing RE and Either`() {
        //given
        val aDependencies: ADependencies = mockk()
        val bRepository: BRepository = mockk()
        val storage: Storage = mockk()
        every { aDependencies.foo() } returns 13
        every { aDependencies.bar("b") } returns 2
        every { bRepository.findBiId(any()) } returns BEntity(13, 5)
        every { storage.save(any()) } returns Unit
        val ctx = AppDependencies(aDependencies, bRepository)
        val app = ApplicationEitherREMixing(ctx, storage)
        val expectedEntity = BEntity(13, 10)
        //when
        app.appX("b")
        //then
        verify(exactly = 1) {
            aDependencies.foo()
            aDependencies.bar("b")
            bRepository.findBiId(13)
            storage.save(expectedEntity)
        }
    }

}
