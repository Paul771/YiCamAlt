// FILE: EventModule.kt
// VERSION: 0.2.1
// START_MODULE_CONTRACT
//   PURPOSE: Hilt wiring for the alert feed wire layer.
//   SCOPE: provides AlertListApi from the shared Retrofit. AlertSyncService, MediaRefStore and EventCacheCleaner
//     are @Inject singletons and need no binding here.
//   DEPENDS: M-EVENT, M-HTTP
//   LINKS: M-EVENT
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

// START_MODULE_MAP
//   EventModule - provides AlertListApi from the shared Retrofit.
//   (no port) - the UI depends on AlertSyncService directly: one implementation, no interface (YAGNI). If the
//     05-04 view-model tests need a fake, they introduce a small EventFeedSource interface there.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.1 - GSD plan 05-02: comment-only; documents that the sync service is used without a port.
//   PREV_CHANGE: v0.2.0 - GSD plan 05-01: re-pointed to GET v2/alert/list per 05-RESEARCH §1 and 05-PROBE.
//     Removed EventBindsModule (EventServiceModule / EventTimelinePort deleted) and the YiCloudEventApi
//     provider; provides AlertListApi instead.
// END_CHANGE_SUMMARY

@Module
@InstallIn(SingletonComponent::class)
object EventModule {

    // START_CONTRACT: provideAlertListApi
    //   PURPOSE: Retrofit service of GET v2/alert/list on the shared Yi client (region base URL, app headers).
    //   INPUTS: { retrofit: Retrofit - shared singleton }
    //   OUTPUTS: { AlertListApi }
    //   SIDE_EFFECTS: none
    //   LINKS: AlertListApi
    // END_CONTRACT: provideAlertListApi
    @Provides
    @Singleton
    fun provideAlertListApi(retrofit: Retrofit): AlertListApi =
        retrofit.create(AlertListApi::class.java)
}
