// FILE: EventModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt wiring for the event timeline graph.
//   SCOPE: provides YiCloudEventApi from the shared Retrofit; binds EventServiceModule as EventTimelinePort.
//   DEPENDS: M-EVENT, M-HTTP
//   LINKS: M-EVENT
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

// START_MODULE_MAP
//   EventBindsModule - binds EventServiceModule as EventTimelinePort.
//   EventModule - provides YiCloudEventApi from the shared Retrofit.
// END_MODULE_MAP

@Module
@InstallIn(SingletonComponent::class)
abstract class EventBindsModule {
    @Binds
    @Singleton
    abstract fun bindEventTimelinePort(impl: EventServiceModule): EventTimelinePort
}

@Module
@InstallIn(SingletonComponent::class)
object EventModule {

    @Provides
    @Singleton
    fun provideYiCloudEventApi(retrofit: Retrofit): YiCloudEventApi =
        retrofit.create(YiCloudEventApi::class.java)
}
