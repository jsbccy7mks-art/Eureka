package com.zapp.tv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Playlist::class, Category::class, Channel::class, Programme::class, Favorite::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun favoriteDao(): FavoriteDao

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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "zapp.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
