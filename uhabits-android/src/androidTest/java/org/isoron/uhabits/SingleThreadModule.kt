package org.isoron.ADAY

import dagger.Module
import dagger.Provides
import org.isoron.ADAY.core.AppScope
import org.isoron.ADAY.core.tasks.SingleThreadTaskRunner
import org.isoron.ADAY.core.tasks.TaskRunner

@Module
internal object SingleThreadModule {
    @JvmStatic
    @Provides
    @AppScope
    fun provideTaskRunner(): TaskRunner {
        return SingleThreadTaskRunner()
    }
}
