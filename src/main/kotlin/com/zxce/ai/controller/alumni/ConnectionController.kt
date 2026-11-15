package com.zxce.ai.controller.alumni

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.service.alumni.ConnectionService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*

/**
 * 校友社交关系控制器
 *
 * 提供校友之间的关注、取消关注、关系列表、关系状态、人脉度、
 * 智能推荐、关系路径、隐私设置等完整社交关系管理功能。
 */
@HostForward
@RestController
@RequestMapping("/api/alumni/connection")
class ConnectionController {

    @Autowired
    lateinit var connectionService: ConnectionService

    // ========================================================
    // 基础关注功能
    // ========================================================

    @PostMapping("/follow")
    suspend fun follow(context: Context, @RequestBody params: Bundle): Any {
        return connectionService.follow(context, params["targetUserId"].toString())
    }

    @PostMapping("/unfollow")
    suspend fun unfollow(context: Context, @RequestBody params: Bundle): Any {
        return connectionService.unfollow(context, params["targetUserId"].toString())
    }

    // ========================================================
    // 人脉列表（带筛选和搜索）
    // ========================================================

    data class ListParam(
        var status: Int? = null,
        var pageNo: Int = 1,
        var pageSize: Int = 20,
        var filterType: String? = null,
        var keyword: String? = null
    )

    @PostMapping("/list")
    suspend fun list(context: Context, @RequestBody params: ListParam): Bundle {
        val (data, total) = if (!params.keyword.isNullOrBlank()) {
            connectionService.searchConnections(context, params.keyword!!, params.pageNo, params.pageSize)
        } else if (!params.filterType.isNullOrBlank() && params.filterType != "all") {
            connectionService.getFilteredConnections(context, params.filterType!!, params.pageNo, params.pageSize)
        } else {
            connectionService.getConnections(context, params.status, params.pageNo, params.pageSize)
        }
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    data class SearchParam(
        var keyword: String = "",
        var pageNo: Int = 1,
        var pageSize: Int = 20
    )

    @PostMapping("/search")
    suspend fun search(context: Context, @RequestBody params: SearchParam): Bundle {
        val (data, total) = connectionService.searchUsersForGraph(
            context, params.keyword, params.pageNo, params.pageSize
        )
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    @PostMapping("/status")
    suspend fun status(context: Context, @RequestBody params: Bundle): Bundle {
        return bundleScope {
            "status" set connectionService.getConnectionStatus(context, params["targetUserId"].toString())
        }
    }

    @PostMapping("/degree")
    suspend fun degree(context: Context, @RequestBody params: Bundle): Bundle {
        return bundleScope {
            "degree" set connectionService.getConnectionDegree(context, params["targetUserId"].toString())
        }
    }

    // ========================================================
    // 人脉统计
    // ========================================================

    @PostMapping("/stats")
    suspend fun stats(context: Context): Bundle {
        return connectionService.getConnectionStats(context)
    }

    // ========================================================
    // 智能推荐
    // ========================================================

    data class RecommendParam(
        var keyword: String = "",
        var filters: List<String> = emptyList(),
        var pageNo: Int = 1,
        var pageSize: Int = 20
    )

    @PostMapping("/recommend")
    suspend fun recommend(context: Context, @RequestBody params: RecommendParam): Bundle {
        val (data, total) = connectionService.getSmartRecommendations(
            context, params.keyword, params.filters, params.pageNo, params.pageSize
        )
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    @PostMapping("/recommend/filters")
    suspend fun recommendFilters(context: Context): Bundle {
        return bundleScope {
            "filters" set connectionService.getSmartFilters(context)
        }
    }

    // ========================================================
    // 关系路径
    // ========================================================

    data class PathParam(var targetUserId: String = "")

    @PostMapping("/path")
    suspend fun path(context: Context, @RequestBody params: PathParam): Bundle? {
        return connectionService.getRelationshipPath(context, params.targetUserId)
    }

    data class BridgeParam(var bridgeUserId: String = "")

    @PostMapping("/bridge")
    suspend fun bridge(context: Context, @RequestBody params: BridgeParam): Bundle? {
        return connectionService.getBridgeUserInfo(context, params.bridgeUserId)
    }

    // ========================================================
    // 关系图谱
    // ========================================================

    data class GraphParam(
        var degreeLimit: Int = 3,
        var maxNodesPerLayer: Int = 30,
        var offset: Int = 0,
        var batchSize: Int = 10
    )

    @PostMapping("/graph")
    suspend fun graph(context: Context, @RequestBody params: GraphParam): Bundle {
        return connectionService.getGraph(context, params.degreeLimit, params.maxNodesPerLayer, params.offset, params.batchSize)
    }

    // ========================================================
    // 隐私设置
    // ========================================================

    @PostMapping("/privacy")
    suspend fun getPrivacy(context: Context): Bundle {
        return connectionService.getPrivacySettings(context)
    }

    @PostMapping("/privacy/update")
    suspend fun updatePrivacy(context: Context, @RequestBody params: Bundle): Any {
        return connectionService.updatePrivacySettings(context, params)
    }

    // ========================================================
    // 指定用户的联系人图谱
    // ========================================================

    data class UserGraphParam(
        var targetUserId: String = "",
        var degreeLimit: Int = 3,
        var offset: Int = 0,
        var batchSize: Int = 10
    )

    @PostMapping("/user-graph")
    suspend fun userGraph(context: Context, @RequestBody params: UserGraphParam): Bundle {
        return connectionService.getUserGraph(context, params.targetUserId, params.degreeLimit, params.offset, params.batchSize)
    }
}
