package pl.rebyrg.learningarrow

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import arrow.core.extensions.id.comonad.extract
import arrow.core.flatMap
import arrow.mtl.Reader
import arrow.mtl.extensions.fx
import arrow.typeclasses.internal.IdBimonad
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ApplicationAndDomainsInteractionsTest {

    data class DomainError(val message: String)

    class DomainA {

        fun multiplyByA(value: Int): Reader<DomainAConfig, Int> =
            Reader{ it.a() * value }

        fun bValue(name: String): Reader<DomainAConfig, Either<DomainError, Int>> =
            Reader { config ->
                config.b(name)
                    ?.let { Right(it) }
                    ?: Left(DomainError("constant not available: $name"))
            }

        fun value(value: String): Reader<DomainAConfig, Either<DomainError, Int>> =
            Reader.fx(IdBimonad) {
                val x = bValue(value).bind()
                val y = x.map { multiplyByA(it).bind() }
                y
            }
    }

    interface DomainAConfig{
        fun a(): Int
        fun b(name: String): Int?
    }

    class DomainB {
        fun double(id: Int): Reader<ReadonlyRepositoryB, Either<DomainError, EntityB>> =
            modify(id) { double() }

        fun add(id: Int, value: Int): Reader<ReadonlyRepositoryB, Either<DomainError, EntityB>> =
            modify(id) { add(value) }

        private fun modify(id: Int, operation: EntityB.() -> EntityB): Reader<ReadonlyRepositoryB, Either<DomainError, EntityB>> =
            Reader { repo ->
                repo.findBiId(id)
                    ?.let { entity ->
                        Right(entity.operation())
                    }
                    ?: Left(DomainError("can not find $id"))
            }
    }

    data class EntityB(val id: Int, val data: Int) {
        fun double() = copy(data = data * 2)
        fun add(value: Int) = copy(data = data + value)
    }

    interface ReadonlyRepositoryB {
        fun findBiId(id: Int): EntityB?
    }

    data class AppDependencies(val a: DomainAConfig, val b: ReadonlyRepositoryB)

    data class Application(val ctx: AppDependencies, val storage: Storage) {
        val domainA = DomainA()
        val domainB = DomainB()

        fun foo() {
            val r: Reader<AppDependencies, Either<DomainError, EntityB>> = Reader.fx(IdBimonad) {
                val a: Either<DomainError, Int> = domainA
                    .value("b")
                    .local<AppDependencies> { appD -> appD.a }
                    .bind()
                val b: Either<DomainError, EntityB> = a
                    .flatMap {
                        domainB
                            .double(it)
                            .local<AppDependencies> { appD -> appD.b }
                            .bind()
                    }
                b
            }
            val result = r
                .run(ctx)
                .extract()
            result.fold({ Unit }, { storage.save(it) } )
        }
    }

    interface Storage{
        fun save(entity: EntityB)
    }

    @Test
    fun appDomainCompositionTest() {
        //given
        val domainAConfig: DomainAConfig = mockk()
        val repositoryB: ReadonlyRepositoryB = mockk()
        val storage: Storage = mockk()
        every { domainAConfig.a() } returns 13
        every { domainAConfig.b(any()) } returns 2
        every { repositoryB.findBiId(any()) } returns EntityB(26, 5)
        every { storage.save(any()) } returns Unit
        val ctx = AppDependencies(domainAConfig, repositoryB)
        val app = Application(ctx, storage)
        //when
        app.foo()
        //then
        val expectedEntity = EntityB(26, 10)
        verify(exactly = 1) {
            domainAConfig.a()
            domainAConfig.b("b")
            repositoryB.findBiId(26)
            storage.save(expectedEntity)
        }
    }

}
