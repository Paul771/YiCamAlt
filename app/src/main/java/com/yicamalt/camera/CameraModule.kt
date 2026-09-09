// FILE: CameraModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt wiring for the camera registry graph.
//   SCOPE: provides YiCloudDeviceApi from the shared Retrofit; registers CameraRegistry.
//   DEPENDS: M-CAMERA-LIST, M-HTTP
//   LINKS: M-CAMERA-LIST
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
//   CameraBindsModule - binds CameraRegistry as CameraListPort.
// END_MODULE_MAP

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraBindsModule {
    @Binds
    @Singleton
    abstract fun bindCameraListPort(impl: CameraRegistry): CameraListPort
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