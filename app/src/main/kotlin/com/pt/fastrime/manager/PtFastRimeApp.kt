package com.pt.fastrime.manager

import android.app.Application
import com.pt.fastrime.manager.data.AppDatabase
import com.pt.fastrime.manager.data.SettingsStore
import com.pt.fastrime.manager.privilege.PrivilegeManager
import com.pt.fastrime.manager.rime.SchemaRepository

/**
 * 应用级单例容器。
 *
 * 注意：这里不做任何权限探测、不启动协程、不弹窗——权限检测放在 MainViewModel.init，
 * 且是纯后台静默执行，保证冷启动不会出现任何询问或引导界面。
 */
class PtFastRimeApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var privilegeManager: PrivilegeManager
        private set

    lateinit var schemaRepository: SchemaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.get(this)
        // 提前 touch 一次，确保 Room 在主线程之外完成首次初始化前的类加载
        database.schemaDao()
        settingsStore = SettingsStore(this)
        privilegeManager = PrivilegeManager(this)
        schemaRepository = SchemaRepository(this, settingsStore)
    }
}
