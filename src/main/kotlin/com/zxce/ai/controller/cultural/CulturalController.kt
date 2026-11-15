package com.zxce.ai.controller.cultural

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.service.cultural.CulturalService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*

/**
 * 文创控制器
 *
 * 提供校友文创相关的RESTful API，包括捐赠管理、认领物品管理和认领管理等功能。
 * 所有接口路径前缀为 /api/alumni/cultural，需要用户登录认证。
 */
@HostForward
@RestController
@RequestMapping("/api/alumni/cultural")
class CulturalController {

    @Autowired
    lateinit var culturalService: CulturalService

    // ========================================================
    // 捐赠管理
    // ========================================================

    @PostMapping("/donation/create")
    suspend fun createDonation(context: Context, @RequestBody data: Bundle): Any {
        return culturalService.createDonation(context, data)
    }

    data class DonationListParam(
        var pageNo: Int = 1,
        var pageSize: Int = 20
    )

    @PostMapping("/donation/list")
    suspend fun donationList(context: Context, @RequestBody params: DonationListParam): Bundle {
        val (data, total) = culturalService.getDonationList(context, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    @PostMapping("/donation/my")
    suspend fun myDonations(context: Context, @RequestBody params: DonationListParam): Bundle {
        val (data, total) = culturalService.getMyDonations(context, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    // ========================================================
    // 认领物品管理
    // ========================================================

    @PostMapping("/claim-item/create")
    suspend fun createClaimItem(context: Context, @RequestBody data: Bundle): Any {
        return culturalService.createClaimItem(context, data)
    }

    data class ClaimItemListParam(
        var pageNo: Int = 1,
        var pageSize: Int = 20
    )

    @PostMapping("/claim-item/list")
    suspend fun claimItemList(context: Context, @RequestBody params: ClaimItemListParam): Bundle {
        val (data, total) = culturalService.getClaimItemList(context, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    // ========================================================
    // 认领管理
    // ========================================================

    @PostMapping("/claim/create")
    suspend fun createClaim(context: Context, @RequestBody data: Bundle): Any {
        return culturalService.createClaim(context, data)
    }

    data class ClaimListParam(
        var pageNo: Int = 1,
        var pageSize: Int = 20
    )

    @PostMapping("/claim/my")
    suspend fun myClaims(context: Context, @RequestBody params: ClaimListParam): Bundle {
        val (data, total) = culturalService.getMyClaims(context, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    // ========================================================
    // 统计
    // ========================================================

    @PostMapping("/stats")
    suspend fun stats(context: Context): Bundle {
        return culturalService.getStats(context)
    }
}