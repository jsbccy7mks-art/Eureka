package com.zapp.tv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Playlist::class, Category::class, Channel::class, Programme::class, Favorite::class, RecentChannel::class, StagedProgramme::class,
        Addon::class, WatchProgress::class, LibraryItem::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentDao(): RecentDao
    abstract fun mediaDao(): MediaDao

    companion object {
        /** Phase 1 -> Phase 2: keeps existing playlists, adds the guide and favourites. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `epgUpdated` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `programmes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, `channelEpgId` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                        "`stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_programmes_playlistId_channelEpgId_start` " +
                        "ON `programmes` (`playlistId`, `channelEpgId`, `start`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, `streamUrl` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_playlistId_streamUrl` " +
                        "ON `favorites` (`playlistId`, `streamUrl`)",
                )
            }
        }

        /** 0.2 -> 0.3: adds recently watched channels. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_channels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, `streamUrl` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`logo` TEXT, `watchedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_channels_playlistId_streamUrl` " +
                        "ON `recent_channels` (`playlistId`, `streamUrl`)",
                )
            }
        }

        /** 0.3 -> 0.4: programme artwork. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `programmes` ADD COLUMN `icon` TEXT")
            }
        }

        /** 0.4.0 -> 0.4.1: separate table for guide downloads; clears leftovers of the old method. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `programmes_staging` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, `channelEpgId` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                        "`stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `icon` TEXT)",
                )
                db.execSQL("DELETE FROM `programmes` WHERE `playlistId` < 0")
            }
        }

        /** 0.5 -> 0.6: add-ons, watch progress and library. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `addons` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`manifestUrl` TEXT NOT NULL, `addonId` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, " +
                        "`description` TEXT, `logo` TEXT, `manifestJson` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_progress` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`videoId` TEXT NOT NULL, `metaId` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`episodeLabel` TEXT, `poster` TEXT, `background` TEXT, `position` INTEGER NOT NULL, " +
                        "`duration` INTEGER NOT NULL, `watched` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_progress_videoId` ON `watch_progress` (`videoId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`metaId` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `poster` TEXT, `addedAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_library_metaId` ON `library` (`metaId`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "zapp.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
