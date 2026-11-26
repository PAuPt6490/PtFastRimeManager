package com.pt.fastrime.manager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地持久化数据库。
 *
 * 版本历史：
 * - v1：单表 schemas；
 * - v2：schemas 增加 packageName 字段（支持一个 zip 包含多个方案）；
 * - v3：schemas 增加 imePackage 字段（方案按目标输入法隔离）。
 *
 * 注意：这里**不再使用破坏性迁移**。之前用过 fallbackToDestructiveMigration，
 * 结果升级时把用户的方案清单整表删了，设备上文件还在、应用里却一条记录都没有，
 * 表现为「方案删不掉、输入法选单里一直有」。现在 v1→v2 走显式 ALTER TABLE 迁移。
 */
@Database(entities = [SchemaEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun schemaDao(): SchemaDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2：补上 packageName 列，老记录默认空串（视为单文件导入的方案）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schemas ADD COLUMN packageName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：补上 imePackage 列，老记录默认空串，启动时按文件位置认领归属。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schemas ADD COLUMN imePackage TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pt_fast_rime.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
