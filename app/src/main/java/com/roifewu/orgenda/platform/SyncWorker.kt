package com.roifewu.orgenda.platform

import android.content.Context
import androidx.work.*
import com.roifewu.orgenda.OrgendaApplication
import com.roifewu.orgenda.core.StorageFailure
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OrgendaApplication
        return try {
            app.ready.await()
            app.repository.sync(
                inputData.getString("workspace") ?: app.repository.state.value.workspace?.id,
                inputData.getBoolean("pull", true),
                manual = inputData.getBoolean("manual", false),
            )
            app.reminders.refresh(
                app.repository.state.value,
                app.settings.state.value.notifications,
            )
            if (app.repository.state.value.files.any { it.draftBlob != null && !it.conflict })
                Result.retry()
            else Result.success()
        } catch (e: StorageFailure.Authentication) {
            app.repository.error(e)
            Result.failure()
        } catch (e: Exception) {
            app.repository.error(e)
            Result.retry()
        }
    }

    companion object {
        fun enqueue(
            context: Context,
            workspace: String? = null,
            pull: Boolean = false,
            manual: Boolean = false,
        ) {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(workDataOf("workspace" to workspace, "pull" to pull, "manual" to manual))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "orgenda-sync-${workspace ?: "active"}",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
        }

        fun periodic(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "orgenda-refresh",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                        .setInputData(workDataOf("pull" to true))
                        .build(),
                )
        }
    }
}
