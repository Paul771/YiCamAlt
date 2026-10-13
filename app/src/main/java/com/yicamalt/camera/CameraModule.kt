// FILE: CameraModule.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt wiring for the camera registry graph.
//   SCOPE: provides YiCloudDeviceApi from the shared Retrofit; registers CameraRegistry; binds the encrypted P2P credential store;
//     binds CameraRegistry as the P2pCredentialsSource port (M-STREAM-CORE).
//   DEPENDS: M-CAMERA-LIST, M-HTTP, M-P2P-CREDS, M-STREAM-CORE
//   LINKS: M-CAMERA-LIST, M-P2P-CREDS
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

// START_MODULE_MAP
//   CameraModule - provides YiCloudDeviceApi/CommandApi/CommandTransport from shared Retrofit.
//   CameraBindsModule - binds CameraRegistry as CameraListPort / LogoutCleanup / P2pCredentialsSource and
//     EncryptedP2pCredentialStore as P2pCredentialStore.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - GSD plan 02-01: binds CameraRegistry as P2pCredentialsSource (live screen credentials port).
//   PREV_CHANGE: v0.3.0 - binds CameraRegistry as LogoutCleanup (synchronous logout wipe, SEC-04).
//   PREV_CHANGE: v0.2.0 - GSD plan 01-12 (SEC-04): binds the encrypted store, the only durable home of P2P secrets.
// END_CHANGE_SUMMARY

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraBindsModule {
    @Binds
    @Singleton
    abstract fun bindCameraListPort(impl: CameraRegistry): CameraListPort

    @Binds
    @Singleton
    abstract fun bindP2pCredentialStore(impl: EncryptedP2pCredentialStore): P2pCredentialStore

    /** AuthRepository.logout runs this synchronously (SEC-04); injected there through a Provider. */
    @Binds
    @Singleton
    abstract fun bindLogoutCleanup(impl: CameraRegistry): com.yicamalt.auth.LogoutCleanup

    @Binds
    @Singleton
    abstract fun bindP2pCredentialsSource(impl: CameraRegistry): com.yicamalt.stream.P2pCredentialsSource
}

@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    @Provides
    @Singleton
    fun provideYiCloudDeviceApi(retrofit: Retrofit): YiCloudDeviceApi =
        retrofit.create(YiCloudDeviceApi::class.java)

    @Provides
    @Singleton
    fun provideYiCloudCommandApi(retrofit: Retrofit): YiCloudCommandApi =
        retrofit.create(YiCloudCommandApi::class.java)

    @Provides
    @Singleton
    fun provideCommandTransport(api: YiCloudCommandApi): CommandTransport =
        CommandTransportRetrofit(api)
}