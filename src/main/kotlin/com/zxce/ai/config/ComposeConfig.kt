package com.zxce.ai.config

import com.zxce.ai.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

/**
 * 合成配置类，继承NodeChainConfig。
 * 管理应用启动时的行为配置，如是否在启动后打开浏览器。
 */
class ComposeConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {
    /** 在启动后打开浏览器 */
    var openBrowserOnStart by notnull(true)
}