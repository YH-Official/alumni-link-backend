package com.zxce.ai.service.alumni

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.model.alumni.*
import com.zxce.ai.model.alumni.query.*
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.success
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.util.*

@Service
class ConnectionService {

    @Autowired
    lateinit var mapper: JsonMapper

    private val log = LoggerFactory.getLogger(ConnectionService::class.java)

    // ========================================================
    // 基础关注/取消关注功能
    // ========================================================

    suspend fun follow(ctx: Context, targetUserId: String): Any = ctx {
        val tid = targetUserId.toUuid()
        val current = ctx.query<QAlkConnection>()
            .userId.eq(ctx.userId.toUuid())
            .targetUserId.eq(tid)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        val degree = calculateDegree(ctx, ctx.userId.toUuid(), tid)
        withContext(Dispatchers.IO) {
            if (current != null) {
                if (current.status == 0) {
                    current.status = 1
                } else if (current.status == 2) {
                    current.status = 3
                }
                current.save()
            } else {
                ctx.createModel<AlkConnection> {
                    this.userId = ctx.userId.toUuid()
                    this.targetUserId = tid
                    this.status = 1
                    this.connectionDegree = degree
                    this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                }.save()
            }
        }
        val reverse = ctx.query<QAlkConnection>()
            .userId.eq(tid)
            .targetUserId.eq(ctx.userId.toUuid())
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        reverse?.let {
            withContext(Dispatchers.IO) {
                it.status = 3
                it.save()
            }
        }
        return success()
    }

    suspend fun unfollow(ctx: Context, targetUserId: String): Any = ctx {
        val tid = targetUserId.toUuid()
        val current = ctx.query<QAlkConnection>()
            .userId.eq(ctx.userId.toUuid())
            .targetUserId.eq(tid)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        withContext(Dispatchers.IO) {
            current?.let {
                if (it.status == 3) {
                    it.status = 2
                    it.save()
                } else {
                    it.status = 0
                    it.save()
                }
            }
        }
        val reverse = ctx.query<QAlkConnection>()
            .userId.eq(tid)
            .targetUserId.eq(ctx.userId.toUuid())
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        reverse?.let {
            withContext(Dispatchers.IO) {
                it.status = 1
                it.save()
            }
        }
        return success()
    }

    // ========================================================
    // 关系程度计算（增强版：实时算法，不存储）
    // ========================================================

    /**
     * 计算两个用户之间的关系程度（0-100分）- 实时算法
     *
     * 权重分配：
     * - 共同联系人：30分（每个共同联系人 +3分，最多30分）
     * - 同校：15分
     * - 同学院/专业：10分
     * - 同届：5分
     * - 同公司：10分
     * - 同行业：5分
     * - 同城：5分
     * - 标签/研究方向匹配：10分
     *
     * @return 关系程度分数（最高100分）
     */
    private suspend fun calculateDegree(ctx: Context, userId1: UUID, userId2: UUID): Int {
        val score = calculateDetailedScore(ctx, userId1, userId2)
        return minOf(score.totalScore, 100)
    }

    private data class DegreeScore(
        val totalScore: Int,
        val reasons: List<String>,
        val mutualCount: Int = 0
    )

    private suspend fun calculateDetailedScore(ctx: Context, userId1: UUID, userId2: UUID): DegreeScore {
        var score = 0
        val reasons = mutableListOf<String>()

        val edu1 = ctx.query<QAlkEducation>().userId.eq(userId1).toAsync().findList()
        val edu2 = ctx.query<QAlkEducation>().userId.eq(userId2).toAsync().findList()
        val work1 = ctx.query<QAlkWorkExperience>().userId.eq(userId1).toAsync().findList()
        val work2 = ctx.query<QAlkWorkExperience>().userId.eq(userId2).toAsync().findList()
        val profile1 = ctx.query<QAlkUserProfile>().userId.eq(userId1).orderBy().id.asc().toAsync().findList().firstOrNull()
        val profile2 = ctx.query<QAlkUserProfile>().userId.eq(userId2).orderBy().id.asc().toAsync().findList().firstOrNull()
        val tags1 = ctx.query<QAlkUserTag>().userId.eq(userId1).toAsync().findList().map { it.tagName }
        val tags2 = ctx.query<QAlkUserTag>().userId.eq(userId2).toAsync().findList().map { it.tagName }

        log.info("【calculateScore】user1={}, user2={}, edu1={}, edu2={}, work1={}, work2={}",
            userId1, userId2, edu1.size, edu2.size, work1.size, work2.size)
        edu1.forEach { log.info("  edu1: school={}, faculty={}, major={}, endYear={}", it.schoolName, it.faculty, it.major, it.endYear) }
        edu2.forEach { log.info("  edu2: school={}, faculty={}, major={}, endYear={}", it.schoolName, it.faculty, it.major, it.endYear) }

        // 共同联系人 +30分
        val mutualCount = countMutualConnections(ctx, userId1, userId2)
        if (mutualCount > 0) {
            val mutualScore = minOf(mutualCount * 3, 30)
            score += mutualScore
            reasons.add("拥有${mutualCount}位共同联系人")
        }

        // 同校 +15分
        val schoolMatch = edu1.any { e1 -> edu2.any { e2 -> e1.schoolName == e2.schoolName } }
        if (schoolMatch) {
            score += 15
            reasons.add("同校校友")
        }

        // 同学院/专业 +10分
        val collegeMatch = edu1.any { e1 -> edu2.any { e2 -> e1.schoolName == e2.schoolName && e1.faculty == e2.faculty } }
        val majorMatch = edu1.any { e1 -> edu2.any { e2 -> e1.schoolName == e2.schoolName && e1.major == e2.major } }
        if (collegeMatch) {
            score += 5
            reasons.add("同学院")
        }
        if (majorMatch) {
            score += 5
            reasons.add("同专业")
        }

        // 同届 +5分
        val yearMatch = edu1.any { e1 -> edu2.any { e2 -> e1.endYear != null && e1.endYear == e2.endYear } }
        if (yearMatch) {
            score += 5
            reasons.add("同届毕业")
        }

        // 同公司 +10分
        val companyMatch = work1.any { w1 -> work2.any { w2 -> w1.companyName == w2.companyName } }
        if (companyMatch) {
            score += 10
            reasons.add("曾在同公司工作")
        }

        // 同行业 +5分
        val industryMatch = (profile1?.industry != null && profile1.industry == profile2?.industry) ||
            work1.any { w1 -> work2.any { w2 -> w1.industry != null && w1.industry == w2.industry } }
        if (industryMatch) {
            score += 5
            reasons.add("同行业从业者")
        }

        // 同城 +5分
        if (profile1?.currentCity != null && profile1.currentCity == profile2?.currentCity) {
            score += 5
            reasons.add("同城")
        }

        // 标签匹配 +10分
        val commonTags = tags1.intersect(tags2.toSet())
        if (commonTags.isNotEmpty()) {
            val tagScore = minOf(commonTags.size * 2, 10)
            score += tagScore
            reasons.add("研究方向相似")
        }

        return DegreeScore(minOf(score, 100), reasons, mutualCount).also {
            log.info("【calculateScore】result: score={}, reasons={}, mutual={}", it.totalScore, it.reasons.joinToString(";"), mutualCount)
        }
    }

    /**
     * 计算两个用户的共同联系人数量
     */
    private suspend fun countMutualConnections(ctx: Context, userId1: UUID, userId2: UUID): Int {
        val user1Connections = getConnectedUserIds(ctx, userId1)
        val user2Connections = getConnectedUserIds(ctx, userId2)

        return user1Connections.intersect(user2Connections).size
    }

    /** 双向查询用户的有效联系人：A→B 或 B→A 存在有效记录即视为有联系（库里存在单向历史数据） */
    private suspend fun getConnectedUserIds(ctx: Context, userId: UUID): Set<UUID> {
        return ctx.query<QAlkConnection>()
            .or()
            .userId.eq(userId)
            .targetUserId.eq(userId)
            .endOr()
            .status.`in`(listOf(1, 3))
            .toAsync().findList()
            .map { if (it.userId == userId) it.targetUserId else it.userId }
            .toSet()
    }

    // ========================================================
    // 获取人脉列表（带筛选和搜索）
    // ========================================================

    /**
     * 获取当前用户的人脉关系列表（分页），按关系程度降序排列
     */
    suspend fun getConnections(ctx: Context, status: Int?, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> {
        val myId = ctx.userId.toUuid()
        val offset = (pageNo - 1) * pageSize

        val allConnections = ctx.query<QAlkConnection>().userId.eq(myId).apply {
            status?.let { this.status.eq(it) }
        }.toAsync().findList()

        // 实时计算每个联系人的关系强度，按分数降序排列
        val scored = allConnections.map { conn ->
            val degreeInfo = calculateDetailedScore(ctx, myId, conn.targetUserId)
            conn to degreeInfo
        }.sortedByDescending { (_, info) -> info.totalScore }

        val total = scored.size
        val paged = scored.drop(offset).take(pageSize)

        val bundles = paged.map { (conn, degreeInfo) ->
            mapper.toBundle(conn).apply {
                this["matchScore"] = degreeInfo.totalScore
                this["matchReasons"] = degreeInfo.reasons
                this["connectionDegree"] = degreeInfo.totalScore
            }
        }
        fillConnectionUserInfo(ctx, bundles)
        return bundles to total
    }

    /**
     * 获取筛选后的人脉列表（按类型：同学/同事/行业/地区）
     */
    suspend fun getFilteredConnections(
        ctx: Context,
        filterType: String,
        pageNo: Int,
        pageSize: Int
    ): Pair<List<Bundle>, Int> {
        val myId = ctx.userId.toUuid()
        val offset = (pageNo - 1) * pageSize

        val myConnections = ctx.query<QAlkConnection>()
            .userId.eq(myId)
            .status.`in`(listOf(1, 2, 3))
            .toAsync().findList()

        val results = mutableListOf<Pair<AlkConnection, Int>>()

        for (conn in myConnections) {
            val tid = conn.targetUserId
            val degreeInfo = calculateDetailedScore(ctx, myId, tid)

            val match = when (filterType) {
                "school" -> degreeInfo.reasons.any { it.contains("同校") || it.contains("同学院") || it.contains("同专业") || it.contains("同届") }
                "work" -> degreeInfo.reasons.any { it.contains("同公司") || it.contains("同行业") }
                "industry" -> degreeInfo.reasons.any { it.contains("同行业") || it.contains("研究方向") }
                "region" -> degreeInfo.reasons.any { it.contains("同城") }
                else -> true
            }

            if (match) {
                results.add(conn to degreeInfo.totalScore)
            }
        }

        results.sortByDescending { it.second }
        val paged = results.drop(offset).take(pageSize)

        val bundles = paged.map { (conn, _) ->
            mapper.toBundle(conn).apply {
                val degreeInfo = calculateDetailedScore(ctx, myId, conn.targetUserId)
                this["matchScore"] = degreeInfo.totalScore
                this["matchReasons"] = degreeInfo.reasons
            }
        }
        fillConnectionUserInfo(ctx, bundles)

        return bundles to results.size
    }

    /**
     * 搜索人脉（按关键词搜索姓名、学校、行业）
     */
    suspend fun searchConnections(
        ctx: Context,
        keyword: String,
        pageNo: Int,
        pageSize: Int
    ): Pair<List<Bundle>, Int> {
        val myId = ctx.userId.toUuid()
        val offset = (pageNo - 1) * pageSize

        log.info("【searchConnections】keyword='{}', page={}, size={}", keyword, pageNo, pageSize)

        val myConnections = ctx.query<QAlkConnection>()
            .userId.eq(myId)
            .status.`in`(listOf(1, 2, 3))
            .toAsync().findList()

        val targetIds = myConnections.map { it.targetUserId }.toSet()
        log.info("【searchConnections】我的联系人总数: {} 人", targetIds.size)
        if (targetIds.isEmpty()) return emptyList<Bundle>() to 0

        val keywordLower = keyword.lowercase()

        val matchingUsers = ctx.query<QSysUserEntity> {
            id.isIn(targetIds)
            or {
                realName.contains(keyword)
                userName.contains(keyword)
            }
            id.asMapKey()
        }.toAsync().findList()
        log.info("【searchConnections】姓名匹配: {} 人", matchingUsers.size)

        val matchingUserIds = matchingUsers.map { it.id }.toSet()

        val eduMatches = ctx.query<QAlkEducation>()
            .userId.`in`(targetIds)
            .schoolName.contains(keyword)
            .toAsync().findList()
            .map { it.userId }
            .toSet()
        log.info("【searchConnections】学校匹配: {} 人", eduMatches.size)

        val workMatches = ctx.query<QAlkWorkExperience>()
            .userId.`in`(targetIds)
            .companyName.contains(keyword)
            .toAsync().findList()
            .map { it.userId }
            .toSet()
        log.info("【searchConnections】公司匹配: {} 人", workMatches.size)

        val profileIndustryMatches = ctx.query<QAlkUserProfile>()
            .userId.`in`(targetIds)
            .industry.contains(keyword)
            .toAsync().findList()
            .map { it.userId }
            .toSet()
        log.info("【searchConnections】行业匹配: {} 人", profileIndustryMatches.size)

        val allMatchIds = matchingUserIds + eduMatches + workMatches + profileIndustryMatches
        log.info("【searchConnections】去重后匹配: {} 人", allMatchIds.size)

        val matchingConns = myConnections.filter { allMatchIds.contains(it.targetUserId) }

        val results = matchingConns.map { conn ->
            mapper.toBundle(conn).apply {
                val degreeInfo = calculateDetailedScore(ctx, myId, conn.targetUserId)
                this["matchScore"] = degreeInfo.totalScore
                this["matchReasons"] = degreeInfo.reasons
            }
        }.sortedByDescending { (it["matchScore"] as? Number)?.toInt() ?: 0 }

        val paged = results.drop(offset).take(pageSize)
        fillConnectionUserInfo(ctx, paged)

        log.info("【searchConnections】返回: {} 条 (共{}), 偏移={}", paged.size, results.size, offset)
        return paged to results.size
    }

    /**
     * 按姓名搜索所有用户（用于关系图谱搜索，包含已有联系人）
     * 支持按真实姓名、用户名模糊搜索，返回匹配用户及其关系评分
     */
    suspend fun searchUsersForGraph(
        ctx: Context,
        keyword: String,
        pageNo: Int = 1,
        pageSize: Int = 20
    ): Pair<List<Bundle>, Int> {
        val myId = ctx.userId.toUuid()
        val offset = (pageNo - 1) * pageSize

        log.info("【searchUsersForGraph】keyword='{}', page={}, size={}", keyword, pageNo, pageSize)

        if (keyword.isBlank()) return emptyList<Bundle>() to 0

        val keywordLower = keyword.trim()

        val matchingUsers = ctx.query<QSysUserEntity> {
            or {
                realName.contains(keyword)
                userName.contains(keyword)
            }
            id.ne(myId)
            orderBy().realName.asc()
            setFirstRow(offset)
            setMaxRows(pageSize)
        }.toAsync().findList()

        val totalCount = ctx.query<QSysUserEntity> {
            or {
                realName.contains(keyword)
                userName.contains(keyword)
            }
            id.ne(myId)
        }.toAsync().findCount()

        log.info("【searchUsersForGraph】匹配: {} 人 (共{})", matchingUsers.size, totalCount)

        val bundles = matchingUsers.map { user ->
            val degreeInfo = calculateDetailedScore(ctx, myId, user.id)
            val isContact = ctx.query<QAlkConnection>()
                .userId.eq(myId)
                .targetUserId.eq(user.id)
                .status.`in`(listOf(1, 2, 3))
                .toAsync().findCount() > 0

            val userBundle = mapper.toBundle(user).apply {
                remove("password")
                remove("phone")
                remove("email")
                remove("isUse")
            }

            bundleOf(
                "user" to userBundle,
                "userId" to user.id.toString(),
                "name" to (user.realName?.takeIf { it.isNotBlank() } ?: user.userName),
                "matchScore" to degreeInfo.totalScore,
                "matchReasons" to degreeInfo.reasons,
                "mutualCount" to countMutualConnections(ctx, myId, user.id),
                "isContact" to isContact
            )
        }.sortedByDescending { (it["matchScore"] as? Number)?.toInt() ?: 0 }

        log.info("【searchUsersForGraph】返回: {} 条", bundles.size)
        return bundles to totalCount
    }

    private suspend fun fillConnectionUserInfo(ctx: Context, connections: List<Bundle>) {
        val userIds = connections.map { it["targetUserId"].toString().toUuid() }.toSet()
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
        connections.forEach { conn ->
            users[conn["targetUserId"].toString().toUuid()]?.let {
                conn["targetUser"] = it
            }
        }
    }

    // ========================================================
    // 人脉统计数据
    // ========================================================

    /**
     * 获取人脉统计数据
     * - 直接联系人：status为1或3的连接数
     * - 可互见关系：status为3的连接数
     * - 本月新增：本月新增的连接数
     */
    suspend fun getConnectionStats(ctx: Context): Bundle {
        val myId = ctx.userId.toUuid()
        val allConnections = ctx.query<QAlkConnection>()
            .userId.eq(myId)
            .status.`in`(listOf(1, 2, 3))
            .toAsync().findList()

        val directContacts = allConnections.filter { it.status == 1 || it.status == 3 }.size
        val mutualContacts = allConnections.filter { it.status == 3 }.size

        val now = LocalDate.now()
        val thisMonthStart = now.withDayOfMonth(1)

        val newThisMonth = allConnections.filter { conn ->
            val created = conn.createdAt
            created != null && created.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() >= thisMonthStart
        }.size

        return bundleScope {
            "directContacts" set directContacts
            "mutualContacts" set mutualContacts
            "newThisMonth" set newThisMonth
        }
    }

    // ========================================================
    // 智能推荐算法
    // ========================================================

    /**
     * 智能推荐：根据关键词匹配推荐校友
     *
     * @param ctx 上下文
     * @param keyword 搜索关键词（学校/专业/行业/公司/标签）
     * @param pageNo 页码
     * @param pageSize 每页数量
     * @return 推荐列表，按匹配度排序
     */
    suspend fun getSmartRecommendations(
        ctx: Context,
        keyword: String,
        filters: List<String> = emptyList(),
        pageNo: Int = 1,
        pageSize: Int = 20
    ): Pair<List<Bundle>, Int> {
        val myId = ctx.userId.toUuid()
        val currentUser = ctx.query<QSysUserEntity>().id.eq(myId).toAsync().findList().firstOrNull()
        log.info("【getSmartRecommendations】user={}, keyword='{}', filters={}, page={}, size={}",
            currentUser?.userName ?: myId, keyword, filters, pageNo, pageSize)

        // 获取已有联系人ID（不重复推荐）
        val existingIds = ctx.query<QAlkConnection>()
            .userId.eq(myId)
            .status.`in`(listOf(1, 2, 3))
            .toAsync().findList()
            .map { it.targetUserId }
            .toSet()

        log.info("【getSmartRecommendations】已有联系人: {} 个", existingIds.size)

        // 排除自己
        val excludeIds = existingIds + myId

        val keywordLower = keyword.lowercase().trim()

        // 搜索匹配用户
        val candidates = mutableListOf<Pair<SysUserEntity, Int>>()

        if (keyword.isNotBlank()) {
            // 按姓名搜索
            val nameMatches = ctx.query<QSysUserEntity> {
                or {
                    realName.contains(keyword)
                    userName.contains(keyword)
                }
                id.notIn(excludeIds)
            }.toAsync().findList()
            log.info("【getSmartRecommendations】姓名匹配: {} 人", nameMatches.size)

            for (user in nameMatches) {
                val degreeInfo = calculateDetailedScore(ctx, myId, user.id)
                if (degreeInfo.totalScore > 0) {
                    candidates.add(user to degreeInfo.totalScore)
                }
            }

            // 按学校搜索
            val eduMatches = ctx.query<QAlkEducation>()
                .schoolName.contains(keyword)
                .userId.notIn(excludeIds)
                .toAsync().findList()
                .map { it.userId }
                .distinct()
            log.info("【getSmartRecommendations】学校匹配: {} 人", eduMatches.size)

            for (uid in eduMatches) {
                if (uid in excludeIds) continue
                val user = ctx.query<QSysUserEntity>().id.eq(uid).toAsync().findList().firstOrNull()
                if (user != null) {
                    val degreeInfo = calculateDetailedScore(ctx, myId, uid)
                    candidates.add(user to degreeInfo.totalScore)
                }
            }

            // 按公司/行业搜索
            val workMatches = ctx.query<QAlkWorkExperience>()
                .companyName.contains(keyword)
                .userId.notIn(excludeIds)
                .toAsync().findList()
                .map { it.userId }
                .distinct()
            log.info("【getSmartRecommendations】公司匹配: {} 人", workMatches.size)

            for (uid in workMatches) {
                if (uid in excludeIds) continue
                val user = ctx.query<QSysUserEntity>().id.eq(uid).toAsync().findList().firstOrNull()
                if (user != null) {
                    val degreeInfo = calculateDetailedScore(ctx, myId, uid)
                    candidates.add(user to degreeInfo.totalScore)
                }
            }

            val industryMatches = ctx.query<QAlkUserProfile>()
                .industry.contains(keyword)
                .userId.notIn(excludeIds)
                .toAsync().findList()
                .map { it.userId }
                .distinct()
            log.info("【getSmartRecommendations】行业匹配: {} 人", industryMatches.size)

            for (uid in industryMatches) {
                if (uid in excludeIds) continue
                val user = ctx.query<QSysUserEntity>().id.eq(uid).toAsync().findList().firstOrNull()
                if (user != null) {
                    val degreeInfo = calculateDetailedScore(ctx, myId, uid)
                    candidates.add(user to degreeInfo.totalScore)
                }
            }
        } else {
            // 关键词为空时，返回所有有关系的用户（排除已有联系人和自己），按关系强度排序
            log.info("【getSmartRecommendations】关键词为空，返回所有潜在推荐")
            val allUsers = ctx.query<QSysUserEntity> {
                id.notIn(excludeIds)
            }.toAsync().findList()
            log.info("【getSmartRecommendations】排除后用户数: {} 人", allUsers.size)

            for (user in allUsers) {
                val degreeInfo = calculateDetailedScore(ctx, myId, user.id)
                if (degreeInfo.totalScore > 0) {
                    candidates.add(user to degreeInfo.totalScore)
                }
            }
            log.info("【getSmartRecommendations】有关系的候选: {} 人", candidates.size)
        }

        // 去重并排序
        val uniqueCandidates = candidates
            .groupBy { it.first.id }
            .map { (_, group) -> group.maxByOrNull { it.second }!! }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        log.info("【getSmartRecommendations】去重后候选: {} 人, 分数范围: {}~{}",
            uniqueCandidates.size,
            uniqueCandidates.lastOrNull()?.second ?: 0,
            uniqueCandidates.firstOrNull()?.second ?: 0)
        uniqueCandidates.forEach { (u, s) ->
            log.info("  candidate: userId={}, score={}", u.id, s)
        }

        // 应用筛选
        val filteredCandidates = if (filters.isNotEmpty()) {
            uniqueCandidates.filter { (user, score) ->
                val degreeInfo = calculateDetailedScore(ctx, myId, user.id)
                filters.all { filter ->
                    when (filter) {
                        "school" -> degreeInfo.reasons.any { it.contains("同校") }
                        "mutual" -> degreeInfo.reasons.any { it.contains("共同联系人") }
                        "industry" -> degreeInfo.reasons.any { it.contains("同行业") }
                        "high" -> score >= 60
                        else -> true
                    }
                }
            }
        } else {
            uniqueCandidates
        }

        val offset = (pageNo - 1) * pageSize
        val paged = filteredCandidates.drop(offset).take(pageSize)

        val bundles = paged.map { (user, score) ->
            val userBundle = mapper.toBundle(user).apply {
                remove("password")
                remove("phone")
                remove("email")
                remove("isUse")
            }
            val degreeInfo = calculateDetailedScore(ctx, myId, user.id)
            bundleOf(
                "user" to userBundle,
                "matchScore" to degreeInfo.totalScore,
                "matchReasons" to degreeInfo.reasons,
                "mutualCount" to countMutualConnections(ctx, myId, user.id)
            )
        }

        val result = bundles to filteredCandidates.size
        log.info("【getSmartRecommendations】返回: {} 条 (共{}), 筛选: {}",
            result.first.size, result.second, filters)
        return result
    }

    /**
     * 获取智能推荐的快速筛选项
     */
    suspend fun getSmartFilters(ctx: Context): List<Bundle> {
        val myId = ctx.userId.toUuid()
        val filters = mutableListOf<Bundle>()

        filters.add(bundleOf("key" to "school", "label" to "同校校友", "icon" to "school"))
        filters.add(bundleOf("key" to "mutual", "label" to "有共同联系人", "icon" to "group"))
        filters.add(bundleOf("key" to "industry", "label" to "同行业", "icon" to "work"))
        filters.add(bundleOf("key" to "high", "label" to "高匹配度", "icon" to "star"))

        return filters
    }

    // ========================================================
    // 关系路径算法（BFS最短路径搜索）
    // ========================================================

    /**
     * 查找当前用户与目标用户之间的关系路径
     * 返回：直接连接(0度) / 一度人脉(共同联系人) / 二度人脉(朋友的朋友) / 三度人脉
     */
    suspend fun getRelationshipPath(
        ctx: Context,
        targetUserId: String
    ): Bundle? {
        val myId = ctx.userId.toUuid()
        val tid = targetUserId.toUuid()
        log.info("【getRelationshipPath】myId={}, targetUserId={}", myId, tid)

        // 检查直接连接
        val directConn = ctx.query<QAlkConnection>()
            .userId.eq(myId)
            .targetUserId.eq(tid)
            .status.`in`(listOf(1, 2, 3))
            .orderBy().id.asc().toAsync().findList().firstOrNull()

        if (directConn != null) {
            log.info("【getRelationshipPath】找到直接连接, status={}", directConn.status)
            val degreeInfo = calculateDetailedScore(ctx, myId, tid)
            return buildPathResult(ctx = ctx,
                path = listOf(myId, tid),
                degree = directConn.connectionDegree,
                reasons = degreeInfo.reasons,
                pathType = "direct"
            )
        }

        // BFS搜索（最多3度）
        val visited = mutableSetOf<UUID>()
        val queue = ArrayDeque<Pair<UUID, List<UUID>>>()
        queue.add(myId to listOf(myId))
        visited.add(myId)

        // 一层（一度人脉）
        val firstDegree = getActiveConnections(ctx, myId)
        log.info("【getRelationshipPath】一度人脉: {} 个", firstDegree.size)
        for (friendId in firstDegree) {
            if (friendId == tid) {
                log.info("【getRelationshipPath】找到一度人脉")
                val degreeInfo = calculateDetailedScore(ctx, myId, tid)
                return buildPathResult(ctx = ctx,
                    path = listOf(myId, friendId),
                    degree = degreeInfo.totalScore,
                    reasons = degreeInfo.reasons,
                    pathType = "first_degree"
                )
            }
        }

        // 二层（二度人脉）
        log.info("【getRelationshipPath】开始搜索二度人脉")
        for (friendId in firstDegree.take(50)) { // 限制范围，避免性能问题
            if (friendId in visited) continue
            visited.add(friendId)
            val friendFriends = getActiveConnections(ctx, friendId)

            // 检查共同好友
            val mutualCount = firstDegree.intersect(friendFriends).size

            for (fofId in friendFriends.take(50)) {
                if (fofId == tid) {
                    log.info("【getRelationshipPath】找到二度人脉, 中间人={}, 共同联系人={}", friendId, mutualCount)
                    val degreeInfo = calculateDetailedScore(ctx, myId, tid)
                    return buildPathResult(ctx = ctx,
                        path = listOf(myId, friendId, fofId),
                        degree = degreeInfo.totalScore,
                        reasons = degreeInfo.reasons + "拥有${mutualCount}位共同联系人",
                        pathType = "second_degree",
                        mutualCount = mutualCount,
                        bridgeUserId = friendId
                    )
                }
            }
        }

        // 三层（三度人脉）- 简化版
        log.info("【getRelationshipPath】开始搜索三度人脉")
        for (friendId in firstDegree.take(20)) {
            val friendFriends = getActiveConnections(ctx, friendId).take(20)

            for (fofId in friendFriends) {
                if (fofId == tid) continue
                if (fofId in visited) continue
                visited.add(fofId)

                val fofFriends = getActiveConnections(ctx, fofId).take(20)
                if (fofFriends.contains(tid)) {
                    log.info("【getRelationshipPath】找到三度人脉, 路径: {}->{}->{}->{}", myId, friendId, fofId, tid)
                    val degreeInfo = calculateDetailedScore(ctx, myId, tid)
                    return buildPathResult(ctx = ctx,
                        path = listOf(myId, friendId, fofId, tid),
                        degree = degreeInfo.totalScore,
                        reasons = degreeInfo.reasons,
                        pathType = "third_degree",
                        bridgeUserId = fofId
                    )
                }
            }
        }

        log.info("【getRelationshipPath】未找到关系路径")
        return null
    }

    private suspend fun getActiveConnections(ctx: Context, userId: UUID): List<UUID> {
        return getConnectedUserIds(ctx, userId).toList()
    }

    private suspend fun buildPathResult(
        ctx: Context,
        path: List<UUID>,
        degree: Int,
        reasons: List<String>,
        pathType: String,
        mutualCount: Int = 0,
        bridgeUserId: UUID? = null
    ): Bundle {
        val userIds = path.map { it.toString() }
        val users = if (userIds.isNotEmpty()) {
            ctx.query<QSysUserEntity> {
                id.`in`(path)
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) ->
                mapper.toBundle(v).apply {
                    remove("password")
                    remove("phone")
                    remove("email")
                    remove("isUse")
                }
            }
        } else {
            emptyMap()
        }

        val pathUsers = path.mapNotNull { id ->
            users[id]?.apply {
                this["userId"] = id.toString()
            }
        }

        val typeLabel = when (pathType) {
            "direct" -> "直接连接"
            "first_degree" -> "一度人脉"
            "second_degree" -> "二度人脉"
            "third_degree" -> "三度人脉"
            else -> "未知"
        }

        val reasonsWithCount = reasons.toMutableList()
        if (mutualCount > 0 && reasons.none { it.contains("共同联系人") }) {
            reasonsWithCount.add(0, "拥有${mutualCount}位共同联系人")
        }

        return bundleScope {
            "pathType" set pathType
            "pathTypeLabel" set typeLabel
            "degree" set degree
            "reasons" set reasonsWithCount
            "mutualCount" set mutualCount
            "pathUsers" set pathUsers
            "bridgeUserId" set (bridgeUserId?.toString())
        }
    }

    /**
     * 获取引荐路径上的桥接用户信息
     */
    suspend fun getBridgeUserInfo(ctx: Context, bridgeUserId: String): Bundle? {
        val user = ctx.query<QSysUserEntity>()
            .id.eq(bridgeUserId.toUuid())
            .toAsync().findList().firstOrNull() ?: return null
        return mapper.toBundle(user).apply {
            remove("password")
            remove("phone")
            remove("email")
            remove("isUse")
        }
    }

    // ========================================================
    // 隐私设置
    // ========================================================

    /**
     * 获取/更新隐私设置
     */
    suspend fun getPrivacySettings(ctx: Context): Bundle {
        val existing = ctx.query<QAlkConnectionPrivacy>()
            .userId.eq(ctx.userId.toUuid())
            .orderBy().id.asc()
            .toAsync().findList().firstOrNull()

        return if (existing != null) {
            mapper.toBundle(existing)
        } else {
            bundleScope {
                "visibility" set "contacts" // contacts=我的人脉可见, all=所有人可见, self=仅自己可见
                "allowRecommendation" set true
                "showMutualCount" set true
            }
        }
    }

    suspend fun updatePrivacySettings(ctx: Context, params: Bundle): Any = ctx {
        val myId = ctx.userId.toUuid()
        val existing = ctx.query<QAlkConnectionPrivacy>()
            .userId.eq(myId)
            .orderBy().id.asc()
            .toAsync().findList().firstOrNull()

        withContext(Dispatchers.IO) {
            if (existing != null) {
                params["visibility"]?.toString()?.let { existing.visibility = it }
                (params["allowRecommendation"] as? Boolean)?.let { existing.allowRecommendation = it }
                (params["showMutualCount"] as? Boolean)?.let { existing.showMutualCount = it }
                existing.save()
            } else {
                ctx.createModel<AlkConnectionPrivacy> {
                    this.userId = myId
                    this.visibility = params["visibility"]?.toString() ?: "contacts"
                    this.allowRecommendation = (params["allowRecommendation"] as? Boolean) ?: true
                    this.showMutualCount = (params["showMutualCount"] as? Boolean) ?: true
                    this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                }.save()
            }
        }
        return success()
    }

    // ========================================================
    // 关系图谱
    // ========================================================

    /**
     * 获取关系图谱数据：中心用户、一度人脉、二度人脉、隐私关系
     */
    suspend fun getGraph(
        ctx: Context,
        degreeLimit: Int = 2,
        maxNodesPerLayer: Int = 30,
        offset: Int = 0,
        batchSize: Int = 10
    ): Bundle {
        val myId = ctx.userId.toUuid()
        return buildRelationGraph(ctx, myId, myId, degreeLimit, maxNodesPerLayer, offset, batchSize, includePrivacy = true)
    }

    // ========================================================
    // 辅助方法
    // ========================================================

    suspend fun getConnectionStatus(ctx: Context, targetUserId: String): Int {
        return ctx.query<QAlkConnection>()
            .userId.eq(ctx.userId.toUuid())
            .targetUserId.eq(targetUserId.toUuid())
            .orderBy().id.asc().toAsync().findList().firstOrNull()?.status ?: 0
    }

    suspend fun getConnectionDegree(ctx: Context, targetUserId: String): Int {
        return ctx.query<QAlkConnection>()
            .userId.eq(ctx.userId.toUuid())
            .targetUserId.eq(targetUserId.toUuid())
            .orderBy().id.asc().toAsync().findList().firstOrNull()?.connectionDegree ?: 0
    }

    suspend fun getUserGraph(
        ctx: Context,
        targetUserId: String,
        degreeLimit: Int = 2,
        offset: Int = 0,
        batchSize: Int = 10
    ): Bundle {
        val myId = ctx.userId.toUuid()
        val targetId = targetUserId.toUuid()
        ctx.query<QSysUserEntity>().id.eq(targetId).toAsync().findList().firstOrNull()
            ?: return bundleScope {
                "centerUser" set bundleOf("userId" to targetUserId, "userName" to "用户不存在")
                "firstDegree" set emptyList<Bundle>()
                "secondDegree" set emptyList<Bundle>()
                "thirdDegree" set emptyList<Bundle>()
                "fourthDegree" set emptyList<Bundle>()
                "privacy" set emptyList<Bundle>()
                "stats" set bundleOf("firstDegreeCount" to 0, "secondDegreeCount" to 0, "thirdDegreeCount" to 0, "fourthDegreeCount" to 0)
                "pagination" set bundleOf("offset" to offset, "batchSize" to batchSize, "firstDegreeTotal" to 0, "secondDegreeTotal" to 0, "thirdDegreeTotal" to 0, "fourthDegreeTotal" to 0, "firstDegreeHasMore" to false, "secondDegreeHasMore" to false, "thirdDegreeHasMore" to false, "fourthDegreeHasMore" to false)
            }
        return buildRelationGraph(ctx, targetId, myId, degreeLimit, maxNodesPerLayer = batchSize * 2, offset = offset, batchSize = batchSize, includePrivacy = false)
    }

    /**
     * 构建关系图谱：按层批量 BFS，层级精确。
     * - 邻接关系双向判定（兼容单向历史数据），网络展开仅取 status∈(1,3) 的有效关系；
     * - 每层发现的节点全部记录层级（visited），展示数量按 matchScore 截断，
     *   因此不会出现“真实三度被标成四度”的边界误差；
     * - 图谱展示不按匹配分过滤，分数仅用于排序。
     *
     * @param centerId 图谱中心用户
     * @param viewerId 展示视角用户（匹配分/共同联系人以此计算）
     */
    private suspend fun buildRelationGraph(
        ctx: Context,
        centerId: UUID,
        viewerId: UUID,
        degreeLimit: Int,
        maxNodesPerLayer: Int,
        offset: Int,
        batchSize: Int,
        includePrivacy: Boolean
    ): Bundle {
        val maxDepth = degreeLimit.coerceIn(1, 4)

        val centerUser = ctx.query<QSysUserEntity>().id.eq(centerId).toAsync().findList().firstOrNull()
        val centerBundle = centerUser?.let {
            mapper.toBundle(it).apply {
                remove("password")
                remove("phone")
                remove("email")
                remove("isUse")
                this["userId"] = centerId.toString()
            }
        } ?: bundleOf("userId" to centerId.toString(), "userName" to "用户不存在")

        // 一度：双向查询（我关注的/关注我的/互关），status 归一化为中心视角
        val firstRows = ctx.query<QAlkConnection>()
            .or()
            .userId.eq(centerId)
            .targetUserId.eq(centerId)
            .endOr()
            .status.`in`(listOf(1, 2, 3))
            .toAsync().findList()
        val firstStatus = linkedMapOf<UUID, Int>()
        for (row in firstRows) {
            val other = if (row.userId == centerId) row.targetUserId else row.userId
            if (other == centerId) continue
            // 反向行语义翻转：对方→中心 的 1(对方关注了中心) 对中心而言是 2(粉丝)，反之亦然，3 互关不变
            val st = if (row.userId == centerId) row.status else when (row.status) {
                1 -> 2
                2 -> 1
                else -> row.status
            }
            firstStatus[other] = maxOf(firstStatus[other] ?: 0, st)
        }

        val scoreCache = mutableMapOf<UUID, DegreeScore>()
        suspend fun scoreOf(id: UUID): DegreeScore =
            scoreCache.getOrPut(id) { calculateDetailedScore(ctx, viewerId, id) }

        // 分层 BFS：rawLayers[0] 为一度
        val rawLayers = mutableListOf<List<Pair<UUID, UUID?>>>()
        rawLayers.add(firstStatus.keys.map { it to null })
        val parentOf = mutableMapOf<UUID, UUID>()
        val visited = mutableSetOf(centerId)
        visited += firstStatus.keys

        var prevLayer = firstStatus.keys.toList()
        for (depth in 2..maxDepth) {
            if (prevLayer.isEmpty()) {
                rawLayers.add(emptyList())
                continue
            }
            // 一次查询加载上一层全部节点的有效邻接（双向）
            val rows = ctx.query<QAlkConnection>()
                .or()
                .userId.`in`(prevLayer)
                .targetUserId.`in`(prevLayer)
                .endOr()
                .status.`in`(listOf(1, 3))
                .toAsync().findList()
            val adj = mutableMapOf<UUID, MutableList<UUID>>()
            for (r in rows) {
                if (r.userId != r.targetUserId) {
                    adj.getOrPut(r.userId) { mutableListOf() }.add(r.targetUserId)
                    adj.getOrPut(r.targetUserId) { mutableListOf() }.add(r.userId)
                }
            }
            // 发现的节点全部标记层级（visited），展示按分数截断
            val discovered = linkedMapOf<UUID, UUID>()
            for (from in prevLayer) {
                for (nb in adj[from].orEmpty()) {
                    if (nb in visited || nb in discovered) continue
                    discovered[nb] = from
                }
            }
            visited += discovered.keys
            val scored = discovered.entries
                .map { (id, p) -> Triple(id, p, scoreOf(id)) }
                .sortedByDescending { it.third.totalScore }
                .take(maxNodesPerLayer)
            for ((id, p) in scored) parentOf[id] = p
            rawLayers.add(scored.map { it.first to it.second })
            prevLayer = scored.map { it.first }
        }
        while (rawLayers.size < 4) rawLayers.add(emptyList())

        // 批量加载用户信息
        val allIds = rawLayers.flatten().map { it.first }.toSet()
        val users = if (allIds.isEmpty()) {
            emptyMap()
        } else {
            ctx.query<QSysUserEntity>().id.`in`(allIds.toList()).toAsync().findList().associate { u ->
                u.id to mapper.toBundle(u).apply {
                    remove("password")
                    remove("phone")
                    remove("email")
                    remove("isUse")
                    this["userId"] = u.id.toString()
                }
            }
        }
        fun nameOf(id: UUID?): String =
            id?.let { users[it]?.get("realName")?.toString() ?: users[it]?.get("userName")?.toString() } ?: ""

        // 祖先链：depth 层节点向上追溯到 targetDepth 层
        fun ancestor(id: UUID, depth: Int, targetDepth: Int): UUID? {
            var cur: UUID? = id
            var d = depth
            while (cur != null && d > targetDepth) {
                cur = parentOf[cur]
                d--
            }
            return cur
        }

        // 组装各层 Bundle
        val firstDegreeAll = rawLayers[0].mapNotNull { (id, _) ->
            val ub = users[id] ?: return@mapNotNull null
            val info = scoreOf(id)
            val st = firstStatus[id] ?: 0
            bundleOf(
                "user" to ub,
                "matchScore" to info.totalScore,
                "matchReasons" to info.reasons,
                "mutualCount" to info.mutualCount,
                "status" to st,
                "relation" to when (st) {
                    3 -> "mutual"
                    1 -> "following"
                    2 -> "fan"
                    else -> "none"
                }
            )
        }.sortedByDescending { (it["matchScore"] as? Number)?.toInt() ?: 0 }

        val secondDegreeAll = rawLayers[1].mapNotNull { (id, p) ->
            val ub = users[id] ?: return@mapNotNull null
            val info = scoreOf(id)
            bundleOf(
                "user" to ub,
                "matchScore" to info.totalScore,
                "matchReasons" to info.reasons + "通过共同好友建立联系",
                "mutualCount" to info.mutualCount,
                "bridgeUserId" to (p?.toString() ?: ""),
                "bridgeUserName" to nameOf(p)
            )
        }

        val thirdDegreeAll = rawLayers[2].mapNotNull { (id, p) ->
            val ub = users[id] ?: return@mapNotNull null
            val info = scoreOf(id)
            val first = p?.let { ancestor(it, 2, 1) }
            bundleOf(
                "user" to ub,
                "matchScore" to info.totalScore,
                "matchReasons" to info.reasons + "通过二度好友建立联系",
                "mutualCount" to info.mutualCount,
                "bridgeUserId" to (first?.toString() ?: ""),
                "bridgeUserName" to nameOf(first),
                "secondBridgeUserId" to (p?.toString() ?: ""),
                "secondBridgeUserName" to nameOf(p)
            )
        }

        val fourthDegreeAll = rawLayers[3].mapNotNull { (id, p) ->
            val ub = users[id] ?: return@mapNotNull null
            val info = scoreOf(id)
            bundleOf(
                "user" to ub,
                "matchScore" to info.totalScore,
                "matchReasons" to info.reasons + "通过三度好友建立联系",
                "mutualCount" to 0,
                "bridgeUserId" to (p?.toString() ?: ""),
                "bridgeUserName" to nameOf(p)
            )
        }

        log.info(
            "【buildGraph】center={}, 一度={}, 二度={}, 三度={}, 四度={}",
            centerId, firstDegreeAll.size, secondDegreeAll.size, thirdDegreeAll.size, fourthDegreeAll.size
        )

        // 分页：只对一度分页，其余层补齐桥接节点（保持原有输出结构）
        val firstDegreeSorted = firstDegreeAll.drop(offset).take(batchSize)
        val shownFirstIds = firstDegreeSorted.mapNotNull { (it.bundle("user")?.get("userId") as? String) }.toSet()
        val secondDegreeSorted = secondDegreeAll.filter { item ->
            val bridgeUid = item["bridgeUserId"]?.toString()
            bridgeUid != null && bridgeUid in shownFirstIds
        }.take(batchSize)

        val thirdDegreeSorted = thirdDegreeAll.take(batchSize)
        val shownSecondIds = secondDegreeSorted.mapNotNull { it.bundle("user")?.get("userId")?.toString() }.toSet()
        val thirdBridgeSecondIds = thirdDegreeSorted.mapNotNull { it["secondBridgeUserId"]?.toString() }.toSet()
        val missingSecondDegree = secondDegreeAll.filter { item ->
            val uid = item.bundle("user")?.get("userId")?.toString()
            uid != null && uid in thirdBridgeSecondIds && uid !in shownSecondIds
        }
        val secondDegreeWithBridges = secondDegreeSorted + missingSecondDegree

        val fourthDegreeSorted = fourthDegreeAll.take(batchSize)
        val shownThirdIds = thirdDegreeSorted.mapNotNull { it.bundle("user")?.get("userId")?.toString() }.toSet()
        val fourthBridgeThirdIds = fourthDegreeSorted.mapNotNull { it["bridgeUserId"]?.toString() }.toSet()
        val missingThirdDegree = thirdDegreeAll.filter { item ->
            val uid = item.bundle("user")?.get("userId")?.toString()
            uid != null && uid in fourthBridgeThirdIds && uid !in shownThirdIds
        }
        val thirdDegreeWithBridges = thirdDegreeSorted + missingThirdDegree

        val hasMoreFirst = offset + batchSize < firstDegreeAll.size

        val privacy: List<Bundle> = if (includePrivacy) {
            firstDegreeAll
                .filter { ((it["status"] as? Number)?.toInt() ?: 0) != 3 }
                .drop(offset)
                .take(3)
                .map { it.apply { this["isPrivacy"] = true } }
        } else {
            emptyList()
        }

        return bundleScope {
            "centerUser" set centerBundle
            "firstDegree" set firstDegreeSorted
            "secondDegree" set secondDegreeWithBridges
            "thirdDegree" set thirdDegreeWithBridges
            "fourthDegree" set fourthDegreeSorted
            "privacy" set privacy
            "stats" set bundleOf(
                "firstDegreeCount" to firstDegreeAll.size,
                "secondDegreeCount" to secondDegreeAll.size,
                "thirdDegreeCount" to thirdDegreeAll.size,
                "fourthDegreeCount" to fourthDegreeAll.size,
                "mutualCount" to firstStatus.values.count { it == 3 }
            )
            "pagination" set bundleOf(
                "offset" to offset,
                "batchSize" to batchSize,
                "firstDegreeTotal" to firstDegreeAll.size,
                "secondDegreeTotal" to secondDegreeAll.size,
                "thirdDegreeTotal" to thirdDegreeAll.size,
                "fourthDegreeTotal" to fourthDegreeAll.size,
                "firstDegreeHasMore" to hasMoreFirst,
                "secondDegreeHasMore" to hasMoreFirst,
                "thirdDegreeHasMore" to hasMoreFirst,
                "fourthDegreeHasMore" to hasMoreFirst
            )
        }
    }

}
