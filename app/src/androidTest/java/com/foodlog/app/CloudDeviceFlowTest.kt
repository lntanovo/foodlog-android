package com.foodlog.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foodlog.app.cloud.*
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in synthetic integration test: use dedicated emulators, never a personal device. */
@RunWith(AndroidJUnit4::class)
class CloudDeviceFlowTest {
    @Test fun backupOnAThenRestoreOnB() = runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cloudSyntheticOnly")=="yes")
        val role=requireNotNull(args.getString("cloudRole"))
        require(role in listOf("upload","restore"))
        val username=requireNotNull(args.getString("cloudUsername"))
        val password=requireNotNull(args.getString("cloudPassword"))
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoodLogApplication
        val repo=app.repository
        repo.initialize()
        val sessions=app.cloudSessions
        val beforeLogin=repo.snapshot()
        sessions.login(username,password)
        assertEquals(beforeLogin.meta,repo.snapshot().meta)
        val identity=checkNotNull(sessions.identity.value)
        // Exercise the real Android Keystore across session-manager reconstruction.
        assertEquals(identity,CloudSessionManager(app).identity.value)
        val client=CloudBackupClient(sessions)
        val transfer=File(app.filesDir,"synthetic-cloud-e2e.foodlog.gz")
        if(role=="upload") {
            val seed=repo.parse(app.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
            val date="2026-10-17"
            val version=seed.versions.first()
            val meal=Meal(date=date,status="POSTED",items=listOf(NutritionCalculator.item(version,"60","G")),note="Synthetic cloud A")
            val draft=Meal(date="2026-10-18",items=listOf(NutritionCalculator.item(version,"30","G")),note="Synthetic draft")
            val plan=TargetPlan(effectiveFrom=date,mode="PER_KG",referenceWeight="65",training=Macros("1.5","3","0.8"),rest=Macros("1.5","2.5","0.8"))
            val target=checkNotNull(NutritionCalculator.targetOrNull(plan,date,"TRAINING"))
            val fixture=seed.copy(kind="FULL_BACKUP",meta=Meta(profile=Profile("Synthetic A","170",25,"")),
                meals=listOf(meal,draft),days=listOf(DayLog(date,training="STRENGTH",dietDayType="TRAINING",note="Synthetic day")),
                bodies=(0..3).map { BodyRecord("2026-10-${16+it}",weight="${65+it}.2") },
                plans=listOf(plan),targets=listOf(target),
                origins=listOf(ImportOrigin("synthetic_cloud","meal/one",meal.id,"synthetic-fingerprint")),
                templates=listOf(MealTemplate(name="Synthetic template",items=meal.items)))
            repo.restore(fixture)
            transfer.outputStream().use { repo.writeBackup(it) }
            val contents=transfer.inputStream().use { repo.readBackup(it) }
            val summary=cloudSummary(contents)
            val id=uid()
            val record=CloudBackupRecord(backupId=id,objectPath="${identity.uid}/$id.foodlog.gz",sha256=cloudSha256(transfer),
                sizeBytes=transfer.length(),appVersion=BuildConfig.VERSION_NAME,schemaVersion=3,
                deviceName="Synthetic device A",datasetId=contents.meta.datasetId,recordCounts=summary.counts,
                firstDate=summary.firstDate,lastDate=summary.lastDate,clientRevision=contents.meta.revision)
            val saved=client.upload(identity,record,transfer)
            assertEquals(identity.uid,saved.ownerId)
            // Retrying after a lost response reuses the same immutable version.
            assertEquals(saved,client.upload(identity,record,transfer))
            assertEquals(1,client.list(identity).count { it.backupId==id })
        } else {
            repo.day(DayLog("2026-10-19",note="Synthetic device B before restore"),BodyRecord("2026-10-19",weight="80"))
            val before=repo.snapshot()
            val selected=client.list(identity).first { it.deviceName=="Synthetic device A" }
            client.download(identity,selected,transfer)
            val incoming=transfer.inputStream().use { repo.readBackup(it) }
            assertEquals(before.meta,repo.snapshot().meta) // Download/preview cannot restore implicitly.
            val protection=repo.restore(incoming,before.meta.datasetId,before.meta.revision) { sessions.requireIdentity(identity) }
            fun normalized(d:Dataset)=d.copy(exportedAt="",meta=d.meta.copy(revision=0))
            assertEquals(normalized(incoming),normalized(repo.snapshot()))
            assertEquals(2,repo.snapshot().meals.size); assertEquals(4,repo.snapshot().bodies.size)
            val previous=protection.inputStream().use { repo.readBackup(it) }
            assertEquals(normalized(before),normalized(previous))
            val after=repo.snapshot()
            sessions.logout()
            assertEquals(normalized(after),normalized(repo.snapshot()))
        }
    }
}
