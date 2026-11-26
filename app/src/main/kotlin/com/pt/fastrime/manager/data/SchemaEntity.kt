package com.pt.fastrime.manager.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条本地方案记录。
 *
 * 方案文件本体存放在应用私有目录（localDir），只有“安装到目标输入法”时才通过高权限
 * 复制进 Rime 数据目录；这样卸载应用或误删系统目录都能从私有副本恢复。
 */
@Entity(
    tableName = "schemas",
    indices = [Index(value = ["schemaId"], unique = true)],
)
data class SchemaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Rime 的 schema id，例如 rime_ice、double_pinyin；来自 yaml 顶层的 schema: 字段 */
    val schemaId: String,
    /** 展示名，取 yaml 里的 name: 字段，缺失时退回 schemaId */
    val displayName: String,
    /** 关联文件名，多个用 | 分隔（.schema.yaml 与 .dict.yaml 成对导入） */
    val fileNames: String,
    /** 应用私有目录下的存放目录，绝对路径 */
    val localDir: String,
    val importedAt: Long,
    /** 是否参与部署：false 表示文件留在私有目录，但不安进 Rime */
    val enabled: Boolean = true,
    /**
     * 所属方案包名（来自 .zip 文件名）。
     * 一个 zip 里可能打包多个方案，它们共享同一批词库文件，删除时要按包整体处理；
     * 单文件导入时为空串。
     */
    val packageName: String = "",
    /**
     * 这条方案属于哪个输入法（包名）。
     * 设备上可能同时装着多个 Rime 前端，各自的方案与词库互不相干，必须按输入法隔离，
     * 否则切换目标输入法时会把另一家的方案当成「失效记录」删掉。
     */
    val imePackage: String = "",
)
