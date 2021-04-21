package pl.rebyrg.learningarrow

import arrow.core.Left
import arrow.fx.ForIO
import arrow.fx.IO
import arrow.fx.extensions.io.monadError.monadError
import arrow.fx.fix
import arrow.mtl.EitherT
import arrow.mtl.EitherTPartialOf
import arrow.mtl.Kleisli
import arrow.mtl.KleisliOf
import arrow.mtl.ReaderT
import arrow.mtl.extensions.EitherTMonad
import arrow.mtl.extensions.eithert.applicativeError.applicativeError
import arrow.mtl.extensions.eithert.monadError.monadError
import arrow.mtl.extensions.fx
import arrow.mtl.value
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

typealias EitherIO<E> = EitherTPartialOf<E, ForIO>
typealias RIO<D, E, A> = ReaderT<D, EitherIO<E>, A>

object RIOApi {
    fun <D> monadError(): EitherTMonad<D, ForIO> = EitherT.monadError(IO.monadError())

    fun <D, E> raiseError(e: E): RIO<D, E, Nothing> =
        RIO.raiseError(EitherT.monadError(IO.monadError()), e)

    fun <D, A> just(a: A): RIO<D, Nothing, A> =
        RIO.just(EitherT.applicativeError(IO.monadError()), a)

    fun <D, E> ask(): RIO<D, E, D> = ReaderT.ask(monadError())
}

fun <D, E, A, B> RIO<D, E, A>.map(f: (A) -> B): RIO<D, E, B> = map(RIOApi.monadError(), f)
fun <D, E, A, B> RIO<D, E, A>.flatMap(f: (A) -> KleisliOf<D, EitherIO<E>, B>): RIO<D, E, B> = flatMap(RIOApi.monadError(), f)


class RIOTest {

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
        fun Int.foo(): RIO<ADependencies, DomainError, Int> =
            RIOApi.ask<ADependencies, DomainError>().map {
                it.foo() * this
            }

        fun bar(name: String): RIO<ADependencies, DomainError, Int> =
            RIOApi.ask<ADependencies, DomainError>().flatMap{
                d -> d.bar(name)
                    ?.let { RIOApi.just(it) }
                    ?: RIOApi.raiseError<ADependencies, DomainError>(DomainError.Wrapped("constant not available: $name"))
            }

        fun fooBar(value: String): RIO<ADependencies, DomainError, Int> =
            Kleisli.Companion.fx(RIOApi.monadError()) {
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
        fun double(id: Int): RIO<BRepository, DomainError, BEntity> =
            modify(id) { double() }

        fun add(id: Int, value: Int): RIO<BRepository, DomainError, BEntity> =
            modify(id) { add(value) }

        private fun modify(id: Int, operation: BEntity.() -> BEntity): RIO<BRepository, DomainError, BEntity> =
            RIOApi.ask<BRepository, DomainError>().flatMap{
                    repo ->
                repo.findBiId(id)
                    ?.let { entity ->
                        RIOApi.just(entity.operation())
                    }
                    ?: RIOApi.raiseError<BRepository, DomainError>(DomainError.Wrapped("can not find $id"))
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
            val result: RIO<AppDependencies, DomainError, BEntity> = Kleisli.Companion.fx(RIOApi.monadError()) {
                val fooBar = aDomain.fooBar(barName).local<AppDependencies> { it.a }.bind()
                val entity = bDomain.double(fooBar).local<AppDependencies> { it.b }.bind()
                entity
            }
            result.run(ctx).value().fix().unsafeRunAsync {
                it.fold(
                    { Left(DomainError.Unexpected(it)) },
                    { it }
                ).map { storage.save(it) }
            }
        }
    }

    @Test
    fun `should fail when bar throws error`() {
        //given
        val aDependencies: ADependencies = mockk()
        val bRepository: BRepository = mockk()
        val storage: Storage = mockk()
        every { aDependencies.foo() } returns 13
        every { aDependencies.bar(not("a")) } throws RuntimeException("bar exception")
        every { bRepository.findBiId(any()) } returns BEntity(26, 5)
        every { storage.save(any()) } returns Unit
        val ctx = AppDependencies(aDependencies, bRepository)
        val app = Application(ctx, storage)
        //when
        app.appX("a")
        //then
        verify(exactly = 1) {
            aDependencies.bar("a")
        }
        verify(exactly = 0) {
            aDependencies.foo()
            bRepository.findBiId(any())
            storage.save(any())
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

}
