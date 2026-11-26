package com.pt.fastrime.manager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SchemaDao {

    @Query("SELECT * FROM schemas ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<SchemaEntity>>

    @Query("SELECT * FROM schemas ORDER BY importedAt DESC")
    suspend fun getAll(): List<SchemaEntity>

    /** 只取当前目标输入法名下的方案；imePackage 为空的历史记录也带出来（由调用方回填归属）。 */
    @Query(
        "SELECT * FROM schemas WHERE imePackage = :imePackage OR imePackage = '' " +
            "ORDER BY importedAt DESC",
    )
    fun observeForIme(imePackage: String): Flow<List<SchemaEntity>>

    @Query(
        "SELECT * FROM schemas WHERE imePackage = :imePackage OR imePackage = '' " +
            "ORDER BY importedAt DESC",
    )
    suspend fun getAllForIme(imePackage: String): List<SchemaEntity>

    @Query("SELECT * FROM schemas WHERE schemaId = :schemaId LIMIT 1")
    suspend fun findBySchemaId(schemaId: String): SchemaEntity?

    @Query(
        "SELECT * FROM schemas WHERE schemaId = :schemaId " +
            "AND (imePackage = :imePackage OR imePackage = '') LIMIT 1",
    )
    suspend fun findBySchemaIdForIme(schemaId: String, imePackage: String): SchemaEntity?

    /** 同一个 zip 包导入的方案（共享词库，删除时要整包处理）。 */
    @Query("SELECT * FROM schemas WHERE packageName = :packageName")
    suspend fun findByPackage(packageName: String): List<SchemaEntity>

    @Query(
        "SELECT * FROM schemas WHERE packageName = :packageName " +
            "AND (imePackage = :imePackage OR imePackage = '')",
    )
    suspend fun findByPackageForIme(packageName: String, imePackage: String): List<SchemaEntity>

    /** 老记录没有归属输入法时，归到当前目标输入法名下。 */
    @Query("UPDATE schemas SET imePackage = :imePackage WHERE imePackage = ''")
    suspend fun backfillIme(imePackage: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SchemaEntity): Long

    @Update
    suspend fun update(entity: SchemaEntity)

    @Query("DELETE FROM schemas WHERE schemaId = :schemaId")
    suspend fun deleteBySchemaId(schemaId: String)
}
