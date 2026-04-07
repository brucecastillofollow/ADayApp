package org.bruce.aday

import dagger.Module
import dagger.Provides
import org.bruce.aday.core.AppScope
import org.bruce.aday.core.tasks.SingleThreadTaskRunner
import org.bruce.aday.core.tasks.TaskRunner

@Module
internal object SingleThreadModule {
    @JvmStatic
    @Provides
    @AppScope
    fun provideTaskRunner(): TaskRunner {
        return SingleThreadTaskRunner()
    }
}
