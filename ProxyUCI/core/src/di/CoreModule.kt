package org.uci.proxy.core.di

import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.IdGenerator
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.core.profile.RandomIdGenerator
import org.uci.proxy.core.profile.StoredProfileRepository

/** Lógica común. Depende de los puertos que enlaza [platformModule]. */
val coreModule = module {
    single { AppDispatchers(background = Dispatchers.Default) }
    single<IdGenerator> { RandomIdGenerator() }
    singleOf(::ProfileValidator)
    singleOf(::StoredProfileRepository) { bind<ProfileRepository>() }
}
