package org.maiwithu.maidroid.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class BackupArchiverAndStorageTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `archiver uses sqlite snapshot when available and writes manifest`() {
        val srcDir = tempFolder.newFolder("src_snap")
        val dbFile = File(srcDir, "MaiBot.db").apply { writeBytes("SQLite format 3\u0000LIVE".toByteArray(Charsets.US_ASCII)) }
        File(srcDir, "MaiBot.db-wal").writeText("wal-data")
        val cfgFile = File(srcDir, "bot_config.toml").apply { writeText("name='mai'") }
        val imgFile = File(srcDir, "avatar.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val outZip = File(tempFolder.newFolder("out_snap"), "backup.zip")
        val snapshotDir = tempFolder.newFolder("tmp_snap")
        val archiver = BackupArchiver { _, target ->
            target.writeBytes("SQLite format 3\u0000SNAP".toByteArray(Charsets.US_ASCII))
            true
        }

        val entries = listOf(
            PlannedBackupEntry(dbFile, "maibot/root/data/MaiBot.db", BackupComponent.MaiBotMemory),
            PlannedBackupEntry(cfgFile, "maibot/root/config/bot_config.toml", BackupComponent.MaiBotConfig),
            PlannedBackupEntry(imgFile, "maibot/root/plugins/demo/avatar.png", BackupComponent.Plugins)
        )

        val result = archiver.buildArchive(
            entries = entries,
            outputZip = outZip,
            snapshotDir = snapshotDir,
            appVersion = "0.4.1",
            maiBotVersion = "1.4.8",
            trigger = BackupTrigger.Scheduled,
            components = setOf(BackupComponent.MaiBotMemory, BackupComponent.MaiBotConfig, BackupComponent.Plugins),
            createdAtMillis = 1_764_100_800_000L
        )

        assertEquals(3, result.fileCount)
        assertEquals(0, result.skippedFiles)
        assertEquals(1, result.sqliteSnapshots)
        assertTrue(snapshotDir.listFiles().isNullOrEmpty())

        ZipFile(outZip).use { zip ->
            val dbBytes = zip.getInputStream(zip.getEntry("maibot/root/data/MaiBot.db")).readBytes()
            assertEquals("SQLite format 3\u0000SNAP", String(dbBytes, Charsets.US_ASCII))
            assertEquals(null, zip.getEntry("maibot/root/data/MaiBot.db-wal"))
            assertEquals(ZipEntry.STORED, zip.getEntry("maibot/root/plugins/demo/avatar.png").method)
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("maibot/root/config/bot_config.toml").method)

            val manifestText = zip.getInputStream(zip.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
            val manifest = Json.decodeFromString(BackupManifest.serializer(), manifestText)
            assertEquals("maidroid-backup", manifest.format)
            assertEquals("scheduled", manifest.trigger)
            assertEquals("0.4.1", manifest.appVersion)
            assertEquals("1.4.8", manifest.maiBotVersion)
            assertEquals(3, manifest.fileCount)
            assertEquals(1, manifest.sqliteSnapshots)
        }
    }

    @Test
    fun `archiver falls back to live db with wal and journal when snapshot fails`() {
        val srcDir = tempFolder.newFolder("src_fb")
        val dbFile = File(srcDir, "MaiBot.db").apply { writeBytes("SQLite format 3\u0000LIVE".toByteArray(Charsets.US_ASCII)) }
        File(srcDir, "MaiBot.db-wal").writeText("wal-bytes")
        File(srcDir, "MaiBot.db-journal").writeText("journal-bytes")
        File(srcDir, "MaiBot.db-shm").writeText("shm-ignored")

        val outZip = File(tempFolder.newFolder("out_fb"), "backup.zip")
        val snapshotDir = tempFolder.newFolder("tmp_fb")
        val archiver = BackupArchiver { _, _ -> false }

        val result = archiver.buildArchive(
            entries = listOf(PlannedBackupEntry(dbFile, "maibot/root/data/MaiBot.db", BackupComponent.MaiBotMemory)),
            outputZip = outZip,
            snapshotDir = snapshotDir,
            appVersion = "0.4.1",
            maiBotVersion = null,
            trigger = BackupTrigger.Manual,
            components = setOf(BackupComponent.MaiBotMemory)
        )

        assertEquals(3, result.fileCount)
        assertEquals(0, result.sqliteSnapshots)

        ZipFile(outZip).use { zip ->
            assertEquals("SQLite format 3\u0000LIVE", zip.getInputStream(zip.getEntry("maibot/root/data/MaiBot.db")).readBytes().toString(Charsets.US_ASCII))
            assertEquals("wal-bytes", zip.getInputStream(zip.getEntry("maibot/root/data/MaiBot.db-wal")).readBytes().toString(Charsets.UTF_8))
            assertEquals("journal-bytes", zip.getInputStream(zip.getEntry("maibot/root/data/MaiBot.db-journal")).readBytes().toString(Charsets.UTF_8))
            assertEquals(null, zip.getEntry("maibot/root/data/MaiBot.db-shm"))
        }
    }

    @Test
    fun `file target writes lists prunes and deletes only backup archives`() {
        val dir = tempFolder.newFolder("backup_dir")
        val unrelated = File(dir, "keep-me.txt").apply { writeText("user-file") }
        val target = FileDirectoryBackupTarget(dir)

        val staging = tempFolder.newFolder("staging")
        val names = listOf(
            "maidroid-backup-20261124-040000.zip",
            "maidroid-backup-20261125-040000.zip",
            "maidroid-backup-20261126-040000.zip"
        )
        names.forEachIndexed { idx, name ->
            val tmp = File(staging, "src-$idx.zip").apply { writeText("zip-$idx") }
            val record = target.writeArchive(tmp, name)
            File(record.storageRef).setLastModified(1_000_000L + idx * 10_000L)
        }

        val listed = target.listBackups()
        assertEquals(names.reversed(), listed.map { it.fileName })

        val pruned = target.pruneOldBackups(keepCount = 2)
        assertEquals(1, pruned)
        assertEquals(listOf(names[2], names[1]), target.listBackups().map { it.fileName })
        assertTrue(unrelated.exists())

        assertTrue(target.deleteBackup(target.listBackups().first()))
        assertEquals(listOf(names[1]), target.listBackups().map { it.fileName })
        assertFalse(target.deleteBackup(BackupRecord("keep-me.txt", 9, 0L, unrelated.absolutePath)))
        assertTrue(unrelated.exists())
    }
}
