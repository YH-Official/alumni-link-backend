package com.zxce.ai.config

import com.zxce.ai.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

class ServerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node){
    var port by notnull(7031)
    var hookCssHostUrl by notnull(false)
}