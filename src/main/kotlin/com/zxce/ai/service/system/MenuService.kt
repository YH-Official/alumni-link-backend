package com.zxce.ai.service.system

import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import org.springframework.stereotype.Service

@Service
class MenuService {
    /**
     * 获取菜单配置
     *
     * @return 包含菜单默认配置的Bundle对象，default字段始终为true
     */
    fun getMenuConfig(): Bundle {
        return bundleScope {
            // default 始终应该是 true
            "default" set true
        }
    }
}