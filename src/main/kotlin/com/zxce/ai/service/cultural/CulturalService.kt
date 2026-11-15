package com.zxce.ai.service.cultural

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.model.cultural.*
import com.zxce.ai.model.cultural.query.*
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.*

@Service
class CulturalService {

    @Autowired
    lateinit var mapper: JsonMapper

    // ========================================================
    // 捐赠管理
    // ========================================================

    suspend fun createDonation(ctx: Context, data: Bundle): Any = ctx {
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        data["userId"] = ctx.userId
        val model = ctx.createModel<AlkDonation>(data)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    suspend fun getDonationList(ctx: Context, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val query = ctx.query<QAlkDonation>()
            .status.eq("completed")
            .orderBy().createdAt.desc()
        val total = query.toAsync().findCount()
        val offset = (pageNo - 1) * pageSize
        val list = query.setFirstRow(offset).setMaxRows(pageSize).toAsync().findList()
        val bundles = list.map { mapper.toBundle(it) }
        fillDonationUserInfo(ctx, bundles)
        return bundles to total
    }

    suspend fun getMyDonations(ctx: Context, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val query = ctx.query<QAlkDonation>()
            .userId.eq(ctx.userId.toUuid())
            .orderBy().createdAt.desc()
        val total = query.toAsync().findCount()
        val offset = (pageNo - 1) * pageSize
        val list = query.setFirstRow(offset).setMaxRows(pageSize).toAsync().findList()
        val bundles = list.map { mapper.toBundle(it) }
        fillUserInfoForBundles(ctx, bundles, "userId")
        return bundles to total
    }

    private suspend fun fillDonationUserInfo(ctx: Context, donations: List<Bundle>) {
        val userIds = donations.mapNotNull { it["userId"]?.toString()?.toUuidOrNull() }.toSet()
        if (userIds.isEmpty()) return
        val users = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) ->
            mapper.toBundle(v).apply {
                remove("password")
                remove("phone")
                remove("email")
                remove("isUse")
            }
        }
        donations.forEach { donation ->
            val uid = donation["userId"]?.toString()?.toUuidOrNull()
            uid?.let { users[it]?.let { u -> donation["user"] = u } }
        }
    }

    private suspend fun fillUserInfoForBundles(ctx: Context, bundles: List<Bundle>, userIdField: String) {
        val userIds = bundles.mapNotNull { it[userIdField]?.toString()?.toUuidOrNull() }.toSet()
        if (userIds.isEmpty()) return
        val users = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) ->
            mapper.toBundle(v).apply {
                remove("password")
                remove("phone")
                remove("email")
                remove("isUse")
            }
        }
        bundles.forEach { bundle ->
            val uid = bundle[userIdField]?.toString()?.toUuidOrNull()
            uid?.let { users[it]?.let { u -> bundle["user"] = u } }
        }
    }

    // ========================================================
    // 认领物品管理（学校管理员发布）
    // ========================================================

    suspend fun createClaimItem(ctx: Context, data: Bundle): Any = ctx {
        val model = ctx.createModel<AlkClaimItem>(data)
        withContext(Dispatchers.IO) {
            model.createdBy = ctx.userIdOrNull ?: ctx.user.id
            model.remaining = model.quantity
            model.save()
        }
        return success()
    }

    suspend fun getClaimItemList(ctx: Context, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val query = ctx.query<QAlkClaimItem>()
            .remaining.gt(0)
            .orderBy().createdAt.desc()
        val total = query.toAsync().findCount()
        val offset = (pageNo - 1) * pageSize
        val list = query.setFirstRow(offset).setMaxRows(pageSize).toAsync().findList()
        val bundles = list.map { mapper.toBundle(it) }
        fillUserInfoForBundles(ctx, bundles, "createdBy")
        return bundles to total
    }

    // ========================================================
    // 认领管理
    // ========================================================

    suspend fun createClaim(ctx: Context, data: Bundle): Any = ctx {
        val claimItemId = data["claimItemId"]?.toString()?.toUuid()
            ?: throw ErrorResponseException(HttpStatus.BAD_REQUEST, "缺少claimItemId")
        val quantity = (data["quantity"] as? Number)?.toInt() ?: 1

        // 检查库存
        val item = ctx.query<QAlkClaimItem>().id.eq(claimItemId).toAsync().findOne()
            ?: throw ErrorResponseException(HttpStatus.NOT_FOUND, "认领物品不存在")
        if (item.remaining < quantity) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "库存不足，剩余${item.remaining}件")
        }

        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        data["userId"] = ctx.userId
        val model = ctx.createModel<AlkClaim>(data)

        withContext(Dispatchers.IO) {
            model.save()
            // 扣减库存
            item.remaining -= quantity
            item.save()
        }
        return success()
    }

    suspend fun getMyClaims(ctx: Context, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val query = ctx.query<QAlkClaim>()
            .userId.eq(ctx.userId.toUuid())
            .orderBy().createdAt.desc()
        val total = query.toAsync().findCount()
        val offset = (pageNo - 1) * pageSize
        val list = query.setFirstRow(offset).setMaxRows(pageSize).toAsync().findList()
        val bundles = list.map { mapper.toBundle(it) }
        fillClaimItemInfo(ctx, bundles)
        return bundles to total
    }

    private suspend fun fillClaimItemInfo(ctx: Context, claims: List<Bundle>) {
        val itemIds = claims.mapNotNull { it["claimItemId"]?.toString()?.toUuidOrNull() }.toSet()
        if (itemIds.isEmpty()) return
        val items = ctx.query<QAlkClaimItem> {
            id.isIn(itemIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        claims.forEach { claim ->
            val itemId = claim["claimItemId"]?.toString()?.toUuidOrNull()
            itemId?.let { items[it]?.let { item -> claim["claimItem"] = mapper.toBundle(item) } }
        }
    }

    // ========================================================
    // 统计
    // ========================================================

    suspend fun getStats(ctx: Context): Bundle = ctx {
        val myId = ctx.userId.toUuid()
        val donationCount = ctx.query<QAlkDonation>().userId.eq(myId).toAsync().findCount()
        val claimCount = ctx.query<QAlkClaim>().userId.eq(myId).toAsync().findCount()
        val totalDonations = ctx.query<QAlkDonation>().status.eq("completed").toAsync().findCount()
        val totalClaimItems = ctx.query<QAlkClaimItem>().remaining.gt(0).toAsync().findCount()

        return bundleScope {
            "myDonationCount" set donationCount
            "myClaimCount" set claimCount
            "totalDonations" set totalDonations
            "totalClaimItems" set totalClaimItems
        }
    }
}