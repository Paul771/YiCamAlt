// FILE: AuthModule.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt module wiring AuthRepository, YiCloudAuthApi (from Retrofit), AuthStore, Clock, and AuthProvider binding.
//   SCOPE: @Provides for auth graph; binds AuthRepository as AuthProvider so M-HTTP can inject it.
//   DEPENDS: M-AUTH, M-HTTP, M-LOCAL-DB
//   LINKS: M-HTTP
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import com.yicamalt.network.AuthProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

// START_MODULE_MAP
//   AuthModule - Hilt module: provides YiCloudAuthApi + Clock; binds AuthRepository -> AuthProvider,
//     HttpDeviceListCheck -> DeviceListCheckPort (login region discovery, CAM-02).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - CAM-02: bindDeviceListCheck (HttpDeviceListCheck) for the login region discovery.
//     RegionSelector comes from M-CONFIG (SettingsStoreModule.bindRegionSelector).
// END_CHANGE_SUMMARY

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthProvider(impl: AuthRepository): AuthProvider

    @Binds
    @Singleton
    abstract fun bindAuthStore(impl: EncryptedAuthStore): AuthStore

    @Binds
    @Singleton
    abstract fun bindLoginPort(impl: AuthRepository): LoginPort

    @Binds
    @Singleton
    abstract fun bindSessionSource(impl: AuthRepository): SessionSource

    @Binds
    @Singleton
    abstract fun bindDeviceListCheck(impl: HttpDeviceListCheck): DeviceListCheckPort
}

@Module
@InstallIn(SingletonComponent::class)
object AuthProvidersModule {

    @Provides
    @Singleton
    fun provideYiCloudAuthApi(retrofit: Retrofit): YiCloudAuthApi =
        retrofit.create(YiCloudAuthApi::class.java)

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock
}