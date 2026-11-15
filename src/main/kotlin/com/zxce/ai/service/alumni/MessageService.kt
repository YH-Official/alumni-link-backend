package com.zxce.ai.service.alumni

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.model.alumni.*
import com.zxce.ai.model.alumni.query.*
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.ViewUserRoleEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.model.system.query.QViewUserRoleEntity
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.success
import org.springframework.http.HttpStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

@Service
class MessageService {

    companion object {
        private val log = LoggerFactory.getLogger(MessageService::class.java)
    }

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var broadcastManager: com.zxce.ai.manager.BroadcastManager

    /**
     * 检查内容是否为帖子卡片 JSON，并返回友好预览文本
     * 用于兼容历史数据：修复 messageType 存储错误或 lastMessage 为原始 JSON 的情况
     */
    private fun sanitizePostCardContent(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return content
        return try {
            val node = mapper.readTree(trimmed)
            if (node.isObject && (node.has("postId") || node.has("type"))) {
                val title = node.get("title")?.asText()?.take(30) ?: "分享了一个帖子"
                "📎 $title"
            } else {
                content
            }
        } catch (_: Exception) {
            content
        }
    }

    /**
     * 检查消息内容是否为帖子卡片 JSON，并返回正确的 messageType
     */
    private fun detectMessageType(content: String, currentType: Int): Int {
        if (currentType == 2) return 2
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return currentType
        return try {
            val node = mapper.readTree(trimmed)
            if (node.isObject && (node.has("postId") || (node.has("type") && node.get("type").asText() == "post_card"))) 2 else currentType
        } catch (_: Exception) {
            currentType
        }
    }

    /**
     * 发送私信消息给指定用户
     * 完整流程：权限校验 → 恢复软删除会话 → 查找或创建会话 → 创建消息 → 更新双方会话 → SSE 推送通知
     *
     * @param ctx 上下文，包含用户身份、数据库操作等
     * @param data 消息数据包，支持以下字段：
     *        - targetUserId: 目标用户ID（字符串）
     *        - content: 消息内容（字符串）
     *        - messageType: 消息类型（整数），1=文本消息，2=帖子卡片
     * @return 操作结果，成功返回 success()
     * @throws ErrorResponseException 当用户没有发送权限时抛出 403 错误
     */
    suspend fun sendMessage(ctx: Context, data: Bundle): Any = ctx {
        val targetUserId = data["targetUserId"].toString().toUuid()
        val currentUserId = ctx.userId.toUuid()
        log.info("【sendMessage】STEP1: 开始私信发送, sender={}, target={}", currentUserId, targetUserId)

        val canMessage = canMessageEachOther(ctx, currentUserId, targetUserId)
        if (!canMessage) {
            log.warn("【sendMessage】STEP2: 权限校验失败, sender={}, target={}", currentUserId, targetUserId)
            // 区分失败原因给出精确提示
            val targetConv = ctx.query<QAlkConversation>()
                .userId.eq(targetUserId)
                .targetUserId.eq(currentUserId)
                .orderBy().id.asc().toAsync().findList().firstOrNull()
            val errMsg = when {
                targetConv?.isRejected == true -> "对方已拒绝你的申请，无法发送消息"
                else -> "请等待对方回复后再发送消息"
            }
            throw ErrorResponseException(HttpStatus.FORBIDDEN, errMsg)
        }
        log.info("【sendMessage】STEP2: 权限校验通过, sender={}, target={}", currentUserId, targetUserId)

        // 恢复软删除的会话记录（@SoftDelete 会自动过滤 deleted=true 的记录，无法通过 ORM 查询）
        val restored1 = ctx.database.sqlUpdate("UPDATE alk_conversation SET deleted = false WHERE user_id = :uid AND target_user_id = :tid AND deleted = true")
            .setParameter("uid", currentUserId.toString())
            .setParameter("tid", targetUserId.toString())
            .execute()
        val restored2 = ctx.database.sqlUpdate("UPDATE alk_conversation SET deleted = false WHERE user_id = :uid AND target_user_id = :tid AND deleted = true")
            .setParameter("uid", targetUserId.toString())
            .setParameter("tid", currentUserId.toString())
            .execute()
        if (restored1 > 0 || restored2 > 0) {
            log.info("【sendMessage】STEP3: 恢复软删除会话, restored1={}, restored2={}", restored1, restored2)
        } else {
            log.info("【sendMessage】STEP3: 无需恢复会话")
        }

        var conversation = ctx.query<QAlkConversation>()
            .userId.eq(currentUserId)
            .targetUserId.eq(targetUserId)
            .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()
        if (conversation == null) {
            log.info("【sendMessage】STEP4: 未找到现有会话，创建新会话")
            conversation = ctx.createModel<AlkConversation> {
                this.userId = ctx.userId.toUuid()
                this.targetUserId = targetUserId
                this.createdBy = ctx.userIdOrNull ?: ctx.user.id
            }
            val targetConv = ctx.createModel<AlkConversation> {
                this.userId = targetUserId
                this.targetUserId = ctx.userId.toUuid()
                this.createdBy = ctx.userIdOrNull ?: ctx.user.id
            }
            try {
                withContext(Dispatchers.IO) {
                    conversation.save()
                    targetConv.save()
                }
                log.info("【sendMessage】STEP4: 新会话创建成功, convId={}", conversation.id)
            } catch (e: io.ebean.DuplicateKeyException) {
                log.warn("【sendMessage】STEP4: 并发冲突，重新查询会话: {}", e.message)
                // 并发场景下另一个线程已创建了会话，重新查询
                conversation = ctx.query<QAlkConversation>()
                    .userId.eq(currentUserId)
                    .targetUserId.eq(targetUserId)
                    .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()
                if (conversation == null) throw e
            }
        } else {
            log.info("【sendMessage】STEP4: 使用现有会话, convId={}", conversation.id)
        }

        val message = ctx.createModel<AlkMessage> {
            this.conversationId = conversation.id
            this.senderId = ctx.userId.toUuid()
            this.receiverId = targetUserId
            this.content = data["content"].toString()
            this.messageType = (data["messageType"] as? Number)?.toInt() ?: 1
            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
        }
        log.info("【sendMessage】STEP5: 消息对象创建完成, type={}, content preview={}",
            message.messageType, message.content.take(30))

        val targetConv = ctx.query<QAlkConversation>()
            .userId.eq(targetUserId)
            .targetUserId.eq(ctx.userId.toUuid())
            .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()
        log.info("【sendMessage】STEP6: 目标会话查询结果, targetConvExists={}", targetConv != null)

        withContext(Dispatchers.IO) {
            message.save()
            log.info("【sendMessage】STEP7: 消息保存成功, msgId={}", message.id)

            val lastMsgPreview = if (message.messageType == 2) {
                // 帖子卡片消息：存储简短预览而非原始 JSON
                try {
                    val titleMatch = Regex(""""title"\s*:\s*"([^"]*)"""").find(message.content)
                    val title = titleMatch?.groupValues?.get(1)?.take(30) ?: "分享了一个帖子"
                    "📎 $title"
                } catch (_: Exception) {
                    "📎 分享了一个帖子"
                }
            } else {
                message.content.take(50)
            }
            conversation.lastMessage = lastMsgPreview
            conversation.save()
            log.info("【sendMessage】STEP8: 发送方会话更新成功, lastMessage={}", lastMsgPreview)
            targetConv?.let {
                it.lastMessage = lastMsgPreview
                it.unreadCount++
                it.save()
                log.info("【sendMessage】STEP8: 接收方会话更新成功, unreadCount={}", it.unreadCount)
            }
        }

        log.info("【sendMessage】STEP9: 开始SSE推送, target={}, event=message:received", targetUserId)
        broadcastManager.broadcastToUser("message:received", mapOf(
            "conversationId" to conversation.id.toString(),
            "senderId" to message.senderId.toString(),
            "content" to message.content,
            "messageType" to message.messageType,
            "createdAt" to message.createdAt?.toString()
        ), targetUserId.toString())
        log.info("【sendMessage】STEP10: SSE推送完成, msgId={}, target={}", message.id, targetUserId)

        return success()
    }

    /**
     * 撤回消息
     * 仅发送者本人可在发送后 2 分钟内撤回，撤回后清空内容并标记 isRecalled。
     * 通过现有 SSE 通道向接收方（群聊则向其他成员）推送 message:recalled 事件。
     *
     * @param ctx 上下文
     * @param data 消息数据包，包含 messageId
     * @return 操作结果
     */
    suspend fun recallMessage(ctx: Context, data: Bundle): Any = ctx {
        val messageId = data["messageId"]?.toString()?.trim().orEmpty()
        if (messageId.isEmpty()) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "messageId 不能为空")

        val message = ctx.query<QAlkMessage>()
            .id.eq(messageId.toUuid())
            .findOne()
            ?: throw ErrorResponseException(HttpStatus.NOT_FOUND, "消息不存在")

        if (message.senderId != ctx.userId.toUuid()) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "只能撤回自己发送的消息")
        }
        if (message.isRecalled) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "消息已撤回，请勿重复操作")
        }
        val createdAt = message.createdAt?.time ?: System.currentTimeMillis()
        if (System.currentTimeMillis() - createdAt > 2 * 60 * 1000L) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "超过 2 分钟的消息不可撤回")
        }

        withContext(Dispatchers.IO) {
            message.content = ""
            message.isRecalled = true
            message.recalledAt = java.sql.Timestamp(System.currentTimeMillis())
            message.save()
        }

        val event = mapOf(
            "type" to "message_recalled",
            "messageId" to message.id.toString(),
            "conversationId" to message.conversationId.toString()
        )
        val receiverId = message.receiverId
        if (receiverId != null) {
            broadcastManager.broadcastToUser("message:recalled", event, receiverId.toString())
        } else {
            // 群聊消息：通知除发送者外的其他群成员
            val groupId = message.groupConversationId
            if (groupId != null) {
                ctx.query<QAlkGroupConversationMember>()
                    .groupConversationId.eq(groupId)
                    .deleted.ne(true)
                    .toAsync().findList()
                    .filter { it.userId != message.senderId }
                    .forEach { member ->
                        broadcastManager.broadcastToUser("message:recalled", event, member.userId.toString())
                    }
            }
        }

        return success()
    }

    /**
     * 检查两个用户之间是否可以互发消息
     * 规则：
     *   1. 自己给自己 → 拒绝
     *   2. 被对方拒绝 → 拒绝
     *   3. 我从未给对方发过消息 → 允许（首条申请）
     *   4. 我发过但对方未回复 → 拒绝（等待对方回复）
     *   5. 对方回复过 → 允许（正常通话）
     */
    private suspend fun canMessageEachOther(ctx: Context, userId1: UUID, userId2: UUID): Boolean {
        if (userId1 == userId2) return false

        // 检查是否被对方拒绝（查询对方视角的会话）
        val targetConv = ctx.query<QAlkConversation>()
            .userId.eq(userId2)
            .targetUserId.eq(userId1)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        if (targetConv?.isRejected == true) {
            log.info("【canMessageEachOther】已被对方拒绝, sender={}, target={}", userId1, userId2)
            return false
        }

        // 查询双方会话中是否有对方（userId2）发给自己的消息
        // 如果有，说明对方已回复，允许正常通话
        val myConv = ctx.query<QAlkConversation>()
            .userId.eq(userId1)
            .targetUserId.eq(userId2)
            .orderBy().id.asc().toAsync().findList().firstOrNull()

        // 没有会话 → 首条申请，允许
        if (myConv == null) return true

        // 查询对方是否回复过（对方作为 sender 发给我的消息）
        val repliedByTarget = ctx.query<QAlkMessage>()
            .senderId.eq(userId2)
            .receiverId.eq(userId1)
            .toAsync().findCount()
        return repliedByTarget > 0
    }

    /**
     * 获取指定用户在各层级（学校/学院/专业/班级）的管理员分配信息
     */
    private suspend fun getAdminAssignment(ctx: Context, userId: UUID): Map<String, String?> {
        val assignment = mutableMapOf<String, String?>()
        assignment["schoolId"] = ctx.query<QAlkSchoolAdmin>()
            .userId.eq(userId).orderBy().id.asc().toAsync().findList().firstOrNull()?.schoolId?.toString()
        assignment["collegeId"] = ctx.query<QAlkCollegeAdmin>()
            .userId.eq(userId).orderBy().id.asc().toAsync().findList().firstOrNull()?.collegeId?.toString()
        assignment["majorId"] = ctx.query<QAlkMajorAdmin>()
            .userId.eq(userId).orderBy().id.asc().toAsync().findList().firstOrNull()?.majorId?.toString()
        assignment["classId"] = ctx.query<QAlkClassAdmin>()
            .userId.eq(userId).orderBy().id.asc().toAsync().findList().firstOrNull()?.classId?.toString()
        return assignment
    }

    /**
     * 判断管理员是否在其管辖范围内管理指定用户
     * 依次检查班级→专业→学院→学校四个层级的权限，支持组织表和独立表的 fallback 查询
     */
    private suspend fun isAdminManagingUser(
        ctx: Context,
        adminId: UUID,
        adminRoleType: String,
        adminAssignment: Map<String, String?>,
        targetUserId: UUID,
        targetUserOrgs: Set<UUID>
    ): Boolean {
        // 班级管理员：只能管理班级成员
        adminAssignment["classId"]?.let { classId ->
            val targetClassId = getUserClassId(ctx, targetUserId)
            if (targetClassId != null && targetClassId.toString() == classId) {
                return true
            }
        }

        // 专业管理员：管理专业下所有成员
        adminAssignment["majorId"]?.let { majorId ->
            val majorUUID = UUID.fromString(majorId)
            val majorOrgs = ctx.query<QAlkOrganization>()
                .majorId.eq(majorUUID)
                .deleted.ne(true)
                .toAsync().findList()
            val orgIds = majorOrgs.map { it.id }.toSet()
            if (orgIds.intersect(targetUserOrgs).isNotEmpty()) {
                return true
            }
            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = majorOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass>()
                    .majorId.eq(majorUUID)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty() && ctx.query<QAlkClassAdmin>()
                .classId.isIn(classIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
        }

        // 学院管理员：管理学院下所有成员
        adminAssignment["collegeId"]?.let { collegeId ->
            val collegeUUID = UUID.fromString(collegeId)
            val collegeOrgs = ctx.query<QAlkOrganization>()
                .collegeId.eq(collegeUUID)
                .deleted.ne(true)
                .toAsync().findList()
            val orgIds = collegeOrgs.map { it.id }.toSet()
            if (orgIds.intersect(targetUserOrgs).isNotEmpty()) {
                return true
            }
            // 获取专业ID：优先从组织表，fallback到QAlkMajor表
            var majorIds = collegeOrgs.mapNotNull { it.majorId }.toSet()
            if (majorIds.isEmpty()) {
                majorIds = ctx.query<QAlkMajor>()
                    .collegeId.eq(collegeUUID)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.id }.toSet()
            }
            if (majorIds.isNotEmpty() && ctx.query<QAlkMajorAdmin>()
                .majorId.isIn(majorIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = collegeOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass>()
                    .majorId.isIn(majorIds)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty() && ctx.query<QAlkClassAdmin>()
                .classId.isIn(classIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
        }

        // 学校管理员：管理学校下所有成员
        adminAssignment["schoolId"]?.let { schoolId ->
            val schoolUUID = UUID.fromString(schoolId)
            val schoolOrgs = ctx.query<QAlkOrganization>()
                .schoolId.eq(schoolUUID)
                .deleted.ne(true)
                .toAsync().findList()
            val orgIds = schoolOrgs.map { it.id }.toSet()
            if (orgIds.intersect(targetUserOrgs).isNotEmpty()) {
                return true
            }
            // 获取学院ID：优先从组织表，fallback到QAlkCollege表
            var collegeIds = schoolOrgs.mapNotNull { it.collegeId }.toSet()
            if (collegeIds.isEmpty()) {
                collegeIds = ctx.query<QAlkCollege>()
                    .schoolId.eq(schoolUUID)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.id }.toSet()
            }
            if (collegeIds.isNotEmpty() && ctx.query<QAlkCollegeAdmin>()
                .collegeId.isIn(collegeIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
            // 获取专业ID：优先从组织表，fallback到QAlkMajor表
            var majorIds = schoolOrgs.mapNotNull { it.majorId }.toSet()
            if (majorIds.isEmpty()) {
                majorIds = ctx.query<QAlkMajor>()
                    .collegeId.isIn(collegeIds)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.id }.toSet()
            }
            if (majorIds.isNotEmpty() && ctx.query<QAlkMajorAdmin>()
                .majorId.isIn(majorIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = schoolOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass>()
                    .majorId.isIn(majorIds)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty() && ctx.query<QAlkClassAdmin>()
                .classId.isIn(classIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
        }

        return false
    }

    /**
     * 检查目标用户是否是管理员管理范围内的次级管理员
     * 优先使用 AlkOrganization 表的外键，fallback 到独立的 college/major/class 表
     */
    private suspend fun isTargetSubAdminInScope(
        ctx: Context,
        targetUserId: UUID,
        schoolId: UUID? = null,
        collegeId: UUID? = null,
        majorId: UUID? = null
    ): Boolean {
        // 学校范围：从组织表获取层级ID，检查college_admin/major_admin/class_admin
        if (schoolId != null) {
            val schoolOrgs = ctx.query<QAlkOrganization>()
                .schoolId.eq(schoolId).deleted.ne(true).toAsync().findList()

            // 获取学院ID：优先从组织表，fallback到QAlkCollege表
            var collegeIds = schoolOrgs.mapNotNull { it.collegeId }.toSet()
            if (collegeIds.isEmpty()) {
                collegeIds = ctx.query<QAlkCollege>()
                    .schoolId.eq(schoolId).deleted.ne(true).toAsync().findList().map { it.id }.toSet()
            }
            if (collegeIds.isNotEmpty() && ctx.query<QAlkCollegeAdmin>()
                .collegeId.isIn(collegeIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true

            // 获取专业ID：优先从组织表，fallback到QAlkMajor表
            var majorIdsFromOrgs = schoolOrgs.mapNotNull { it.majorId }.toSet()
            if (majorIdsFromOrgs.isEmpty()) {
                majorIdsFromOrgs = ctx.query<QAlkMajor>()
                    .collegeId.isIn(collegeIds).deleted.ne(true).toAsync().findList().map { it.id }.toSet()
            }
            if (majorIdsFromOrgs.isNotEmpty() && ctx.query<QAlkMajorAdmin>()
                .majorId.isIn(majorIdsFromOrgs).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true

            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = schoolOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass>()
                    .majorId.isIn(majorIdsFromOrgs).deleted.ne(true).toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty() && ctx.query<QAlkClassAdmin>()
                .classId.isIn(classIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
        }

        // 学院范围：从组织表获取层级ID，检查major_admin/class_admin
        if (collegeId != null) {
            val collegeOrgs = ctx.query<QAlkOrganization>()
                .collegeId.eq(collegeId).deleted.ne(true).toAsync().findList()

            // 获取专业ID：优先从组织表，fallback到QAlkMajor表
            var majorIdsFromOrgs = collegeOrgs.mapNotNull { it.majorId }.toSet()
            if (majorIdsFromOrgs.isEmpty()) {
                majorIdsFromOrgs = ctx.query<QAlkMajor>()
                    .collegeId.eq(collegeId).deleted.ne(true).toAsync().findList().map { it.id }.toSet()
            }
            if (majorIdsFromOrgs.isNotEmpty() && ctx.query<QAlkMajorAdmin>()
                .majorId.isIn(majorIdsFromOrgs).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true

            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = collegeOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass>()
                    .majorId.isIn(majorIdsFromOrgs).deleted.ne(true).toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty() && ctx.query<QAlkClassAdmin>()
                .classId.isIn(classIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
        }

        // 专业范围：从组织表获取班级ID，检查class_admin
        if (majorId != null) {
            val majorOrgs = ctx.query<QAlkOrganization>()
                .majorId.eq(majorId).deleted.ne(true).toAsync().findList()

            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = majorOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass>()
                    .majorId.eq(majorId).deleted.ne(true).toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty() && ctx.query<QAlkClassAdmin>()
                .classId.isIn(classIds).userId.eq(targetUserId).deleted.ne(true)
                .toAsync().findList().isNotEmpty()
            ) return true
        }

        return false
    }

    /**
     * 获取指定用户所属的班级ID，用于判断同班同学的消息权限
     */
    private suspend fun getUserClassId(ctx: Context, userId: UUID): UUID? {
        return try {
            ctx.query<QAlkOrganizationMember>()
                .userId.eq(userId)
                .status.eq(1)
                .orderBy().id.asc().toAsync().findList().firstOrNull()?.let { member ->
                    val org = ctx.query<QAlkOrganization>()
                        .id.eq(member.organizationId)
                        .deleted.ne(true)
                        .toAsync().findOne()
                    org?.classId
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取当前用户的所有私聊会话列表
     * 包括最后一条消息预览、目标用户信息，会自动修复帖子卡片消息的显示，并过滤掉孤立的会话记录
     *
     * @param ctx 上下文
     * @return 会话列表，每个会话包含 id、targetUserId、targetUser、lastMessage、unreadCount 等字段
     */
    suspend fun getConversations(ctx: Context): List<Bundle> = ctx {
        val currentUserId = ctx.userId.toUuid()
        
        // 查询当前用户的所有会话（包括群发和私聊）
        val conversations = ctx.query<QAlkConversation>()
            .userId.eq(currentUserId)
            .deleted.ne(true)
            .orderBy().isTop.desc().updatedAt.desc()
            .toAsync().findList()
        
        log.info("getConversations: userId={}, totalConversations={}", currentUserId, conversations.size)
        
        // 如果没有会话，直接返回空列表
        if (conversations.isEmpty()) {
            return@ctx emptyList()
        }

        val result = mapper.toBundle(conversations)
        // 遍历会话，修复历史数据中帖子卡片消息的显示内容（JSON → 友好预览）
        result.forEachIndexed { index, conv ->
            // 显式设置 isRejected，避免 Jackson 对 Kotlin Boolean(is 前缀) 序列化键名不一致
            conv["isRejected"] = conversations[index].isRejected
            val lastMsg = conv["lastMessage"]?.toString() ?: ""
            if (lastMsg.isNotEmpty()) {
                conv["lastMessage"] = sanitizePostCardContent(lastMsg)
            }
        }

        val userIds = result.map { it["targetUserId"]?.toString()?.toUuid() }
            .filterNotNull()
            .toSet()
        val users = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v).apply { remove("password") } }
        result.forEach { conv ->
            val tid = conv["targetUserId"]?.toString()?.toUuid()
            if (tid != null) {
                users[tid]?.let {
                    // 构造目标用户信息：优先使用真实姓名，其次用户名，最后兜底"未知"
                    val userBundle = it.toMutableMap()
                    userBundle["name"] = (userBundle["realName"]?.takeIf { it.toString().isNotBlank() }
                        ?: userBundle["userName"]?.takeIf { it.toString().isNotBlank() }
                        ?: "未知")
                    conv["targetUser"] = userBundle
                }
            }
        }
        // 过滤掉目标用户不存在的会话（数据库中有孤立记录，避免前端显示"未知"）
        val filtered = result.filter { it["targetUser"] != null }
        log.info("getConversations: returning {} conversations (filtered {} orphans)", filtered.size, result.size - filtered.size)
        return@ctx filtered
    }

    /**
     * 获取群发消息会话列表（每个管理员一个会话）
     * 只包含 messageType=5 的群发消息，不与私聊会话混在一起
     */
    suspend fun getBroadcastConversations(ctx: Context): List<Bundle> = ctx {
        val currentUserId = ctx.userId.toUuid()

        // 查询发给当前用户的所有群发消息（messageType=5）
        val broadcastMsgs = ctx.query<QAlkMessage>()
            .receiverId.eq(currentUserId)
            .messageType.eq(5)
            .deleted.ne(true)
            .orderBy().createdAt.desc()
            .toAsync().findList()

        if (broadcastMsgs.isEmpty()) return@ctx emptyList()

        // 按 senderId 分组，每个管理员只保留最后一条消息
        val latestBySender = broadcastMsgs.groupBy { it.senderId }
            .mapValues { it.value.first() }

        val senderIds = latestBySender.keys
        val senders = if (senderIds.isNotEmpty()) {
            ctx.query<QSysUserEntity> {
                id.isIn(senderIds)
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v).apply { remove("password") } }
        } else emptyMap()

        // 计算每个管理员的群发未读数（只统计未读的）
        val unreadCounts = broadcastMsgs.groupBy { it.senderId }
            .mapValues { (_, msgs) -> msgs.count { !it.isRead } }

        val result = latestBySender.map { (senderId, lastMsg) ->
            val sender = senders[senderId]
            val senderName = sender?.let {
                it["realName"]?.takeIf { n -> n.toString().isNotBlank() }
                    ?: it["userName"]?.takeIf { n -> n.toString().isNotBlank() }
                    ?: "管理员"
            }?.toString() ?: "管理员"
            val senderAvatar = sender?.get("avatar")?.toString() ?: ""

            bundleOf(
                "id" to "broadcast_${senderId}",
                "targetUserId" to senderId.toString(),
                "targetUser" to bundleOf(
                    "name" to senderName,
                    "avatar" to senderAvatar
                ),
                "lastMessage" to lastMsg.content.take(50),
                "unreadCount" to (unreadCounts[senderId] ?: 0),
                "updatedAt" to (lastMsg.createdAt?.toString() ?: ""),
                "isBroadcast" to true
            )
        }.sortedByDescending { it["updatedAt"].toString() }

        return@ctx result
    }

    /**
     * 获取当前管理员可群发消息的目标组织列表
     * 根据管理员层级（超级管理员/校级/院级/专业/班级）返回对应的组织，非管理员返回空列表
     *
     * @param ctx 上下文
     * @return 可群发的组织列表（Bundle 格式）
     */
    suspend fun getBroadcastTargets(ctx: Context): List<Bundle> = ctx {
        val currentUserId = ctx.userId.toUuid()

        val myRoleType = ctx.query<QViewUserRoleEntity> {
            userId.eq(currentUserId)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"

        // 检查是否有管理员权限（包括各级管理员分配）
        val hasAdminAssignment = ctx.query<QAlkSchoolAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty() ||
            ctx.query<QAlkCollegeAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty() ||
            ctx.query<QAlkMajorAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty() ||
            ctx.query<QAlkClassAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty()

        if (myRoleType !in listOf("super_admin", "admin", "school_admin", "college_admin", "major_admin", "class_admin") && !hasAdminAssignment) {
            return@ctx emptyList()
        }

        var organizations: List<AlkOrganization>

        // 超级管理员可以群发所有组织
        if (myRoleType == "super_admin") {
            organizations = ctx.query<QAlkOrganization>()
                .status.eq(1)
                .deleted.ne(true)
                .orderBy().school.asc().college.asc().major.asc().className.asc()
                .toAsync().findList()
        } else {
            // 获取当前用户的管理员分配
            val schoolIds = ctx.query<QAlkSchoolAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.schoolId }.toSet()

            val collegeIds = ctx.query<QAlkCollegeAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.collegeId }.toSet()

            val majorIds = ctx.query<QAlkMajorAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.majorId }.toSet()

            val classIds = ctx.query<QAlkClassAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.classId }.toSet()

            // 构建查询条件：根据管理员层级返回对应的组织
            val query = ctx.query<QAlkOrganization>()
                .status.eq(1)
                .deleted.ne(true)

            if (schoolIds.isNotEmpty()) {
                query.schoolId.isIn(schoolIds)
            } else if (collegeIds.isNotEmpty()) {
                query.collegeId.isIn(collegeIds)
            } else if (majorIds.isNotEmpty()) {
                query.majorId.isIn(majorIds)
            } else if (classIds.isNotEmpty()) {
                query.classId.isIn(classIds)
            } else {
                // 没有管理员分配，返回空列表
                return@ctx emptyList()
            }

            organizations = query
                .orderBy().school.asc().college.asc().major.asc().className.asc()
                .toAsync().findList()
        }

        return@ctx mapper.toBundle(organizations)
    }

    /**
     * 获取群发消息列表
     * 支持两种查询模式：新模型按管理员ID查询（所有用户看到同一条消息），旧模型按接收者ID查询（兼容）
     *
     * @param ctx 上下文
     * @param senderId 可选的管理员ID，传入时按新模型查询；为 null 时按旧模型查询当前用户接收的消息
     * @return 群发消息列表，每条消息包含发送者名称和头像信息
     */
    suspend fun getBroadcastMessages(ctx: Context, senderId: String? = null): List<Bundle> = ctx {
        val currentUserId = ctx.userId.toUuid()
        // 查询群发消息（messageType=5）
        // 新模型：所有用户共享一条消息，通过 senderId 查询
        // 旧模型：按 receiverId 查询（兼容）
        val query = ctx.query<QAlkMessage>()
            .messageType.eq(5)

        if (senderId != null) {
            // 新模型：按管理员ID查询，所有用户看到同一条消息
            query.senderId.eq(senderId.toUuid())
        } else {
            // 旧模型兼容：按接收者ID查询
            query.receiverId.eq(currentUserId)
        }
        
        val messages = query
            .orderBy().createdAt.desc()
            .toAsync().findList()

        if (messages.isEmpty()) return@ctx emptyList()

        val result = mapper.toBundle(messages)

        // 获取发送者（管理员）信息
        val senderIds = messages.map { it.senderId }.toSet()
        val senders = if (senderIds.isNotEmpty()) {
            ctx.query<QSysUserEntity> {
                id.isIn(senderIds)
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) -> 
                mapper.toBundle(v).apply { remove("password") }
            }
        } else emptyMap()

        result.forEach { msg ->
            val senderId = msg["senderId"]?.toString()?.toUuidOrNull()
            senders[senderId]?.let { sender ->
                val senderName = sender["realName"]?.takeIf { it.toString().isNotBlank() }
                    ?: sender["userName"]?.takeIf { it.toString().isNotBlank() }
                    ?: "管理员"
                msg["senderName"] = senderName
                msg["senderAvatar"] = sender["avatar"] ?: ""
            }
        }

        return@ctx result
    }

    /**
     * 查询当前管理员自己发送的群发消息历史
     */
    suspend fun getMyBroadcastHistory(ctx: Context): List<Bundle> = ctx {
        val currentUserId = ctx.userId.toUuid()
        
        // 添加日志：检查数据库中是否有该管理员发送的群发消息
        val totalCount = ctx.query<QAlkMessage>()
            .senderId.eq(currentUserId)
            .messageType.`in`(5, 6)
            .deleted.ne(true)
            .toAsync().findList().size
        log.info("getMyBroadcastHistory: userId={}, totalBroadcastMessages={}", currentUserId, totalCount)
        
        val messages = ctx.query<QAlkMessage>()
            .senderId.eq(currentUserId)
            .messageType.`in`(5, 6)
            .deleted.ne(true)
            .orderBy().createdAt.desc()
            .toAsync().findList()

        if (messages.isEmpty()) {
            log.info("getMyBroadcastHistory: No broadcast messages found for userId={}", currentUserId)
            return@ctx emptyList()
        }

        // 群发消息为每个接收者创建了单独的消息记录，按内容去重，同一群发只保留一条
        val uniqueMessages = messages.distinctBy { it.content }
        log.info("getMyBroadcastHistory: Found {} messages, after dedup: {}", messages.size, uniqueMessages.size)
        
        val result = mapper.toBundle(uniqueMessages)
        return@ctx result
    }

    /**
     * 获取当前用户的未读消息总数
     */
    suspend fun getTotalUnreadCount(ctx: Context): Int = ctx {
        val currentUserId = ctx.userId.toUuid()
        val unreadMessages = ctx.query<QAlkMessage>()
            .receiverId.eq(currentUserId)
            .isRead.eq(false)
            .deleted.ne(true)
            .toAsync().findList()
        return@ctx unreadMessages.size
    }

    /**
     * 获取与指定用户的聊天消息历史（分页查询）
     * 自动标记对方消息为已读，修复历史数据中的帖子卡片类型，并附加发送者信息
     *
     * @param ctx 上下文
     * @param targetUserId 目标用户ID
     * @param pageNo 页码（从 1 开始）
     * @param pageSize 每页消息数量
     * @return 消息列表和消息总数的 Pair，消息已按时间正序排列
     */
    suspend fun getMessages(ctx: Context, targetUserId: String, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val tid = targetUserId.toUuid()
        val currentUserId = ctx.userId.toUuid()

        val myConv = ctx.query<QAlkConversation>()
            .userId.eq(currentUserId)
            .targetUserId.eq(tid)
            .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()

        val theirConv = ctx.query<QAlkConversation>()
            .userId.eq(tid)
            .targetUserId.eq(currentUserId)
            .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()

        if (myConv == null && theirConv == null) {
            return@ctx emptyList<Bundle>() to 0
        }

        val convIds = mutableListOf<UUID>()
        myConv?.let { convIds.add(it.id) }
        theirConv?.let { convIds.add(it.id) }

        val offset = (pageNo - 1) * pageSize
        val pagedList = ctx.query<QAlkMessage> {
            conversationId.isIn(convIds)
            orderBy().createdAt.desc()
            setFirstRow(offset)
            setMaxRows(pageSize)
        }.toAsync().findPagedList()

        val messageList: List<AlkMessage> = pagedList.list
        val count: Int = pagedList.totalCount
        val messages: List<Bundle> = mapper.toBundle(messageList)

        val userIds = messages.map { it["senderId"].toString().toUuid() }.toSet()
        val userMap = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        val users: Map<UUID, Bundle> = userMap.mapValues { (_, v) -> mapper.toBundle(v).apply { remove("password") } }

        messages.forEach { msg ->
            val senderId = msg["senderId"].toString().toUuid()
            // 修复历史数据：自动检测帖子卡片类型
            val content = msg["content"]?.toString() ?: ""
            val rawType = (msg["messageType"] as? Number)?.toInt() ?: 1
            msg["messageType"] = detectMessageType(content, rawType)
            // 撤回状态（true 时前端显示"消息已撤回"）
            msg["isRecalled"] = (msg["isRecalled"] as? Boolean) ?: false
            users[senderId]?.let {
                val senderBundle = it.toMutableMap()
                senderBundle["name"] = (senderBundle["realName"]?.takeIf { it.toString().isNotBlank() }
                    ?: senderBundle["userName"]?.takeIf { it.toString().isNotBlank() }
                    ?: "未知")
                msg["sender"] = senderBundle
            }
        }

        withContext(Dispatchers.IO) {
            // 批量标记与该用户的所有未读消息为已读，并清零会话未读数
            ctx.query<QAlkMessage> {
                or {
                    senderId.eq(currentUserId).receiverId.eq(tid)
                    senderId.eq(tid).receiverId.eq(currentUserId)
                }
                receiverId.eq(currentUserId)
                isRead.eq(false)
            }.toAsync().findList().forEach {
                it.isRead = true
                it.save()
            }
            myConv?.let {
                it.unreadCount = 0
                it.save()
            }
        }

        return@ctx messages.reversed() to count
    }

    /**
     * 将与指定用户的所有未读消息标记为已读
     * 同时处理会话消息和广播消息（messageType=5），并重置会话的未读数
     *
     * @param ctx 上下文
     * @param targetUserId 目标用户ID
     * @return 操作结果，成功返回 success()
     */
    suspend fun markAsRead(ctx: Context, targetUserId: String): Any = ctx {
        val tid = targetUserId.toUuid()
        val currentUserId = ctx.userId.toUuid()
        val conversation = ctx.query<QAlkConversation>()
            .userId.eq(currentUserId)
            .targetUserId.eq(tid)
            .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()
        val messages = conversation?.let {
            ctx.query<QAlkMessage>()
                .conversationId.eq(it.id)
                .receiverId.eq(currentUserId)
                .isRead.eq(false)
                .toAsync().findList()
        }
        // 广播消息（messageType=5）不在会话表里，单独标记已读
        val broadcastMessages = ctx.query<QAlkMessage>()
            .senderId.eq(tid)
            .receiverId.eq(currentUserId)
            .messageType.eq(5)
            .isRead.eq(false)
            .deleted.ne(true)
            .toAsync().findList()
        withContext(Dispatchers.IO) {
            messages?.forEach { msg ->
                msg.isRead = true
                msg.save()
            }
            broadcastMessages.forEach { msg ->
                msg.isRead = true
                msg.save()
            }
            conversation?.let {
                it.unreadCount = 0
                it.save()
            }
        }

        return success()
    }

    /**
     * 拒绝对方的私信申请
     * 将自己视角的会话标记为 isRejected=true，对方再发消息时会被 canMessageEachOther 拦截
     * 同时清除未读计数，避免会话列表显示红点
     *
     * @param ctx 上下文
     * @param targetUserId 被拒绝的用户ID
     * @return 操作结果
     */
    suspend fun rejectConversation(ctx: Context, targetUserId: String): Any = ctx {
        val tid = targetUserId.toUuid()
        val currentUserId = ctx.userId.toUuid()
        log.info("【rejectConversation】拒绝申请, user={}, target={}", currentUserId, tid)

        // 查询自己视角的会话（target=tid）
        val myConv = ctx.query<QAlkConversation>()
            .userId.eq(currentUserId)
            .targetUserId.eq(tid)
            .orderBy().id.asc().toAsync().findList().firstOrNull()

        if (myConv == null) {
            log.warn("【rejectConversation】会话不存在, user={}, target={}", currentUserId, tid)
            throw ErrorResponseException(HttpStatus.NOT_FOUND, "会话不存在")
        }

        // 如果已经回复过对方消息，说明已经建立联系，不允许拒绝
        val repliedByMe = ctx.query<QAlkMessage>()
            .senderId.eq(currentUserId)
            .receiverId.eq(tid)
            .toAsync().findCount()
        if (repliedByMe > 0) {
            log.warn("【rejectConversation】已回复过消息，不能拒绝, user={}, target={}", currentUserId, tid)
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "已回复消息，无法拒绝")
        }

        withContext(Dispatchers.IO) {
            myConv.isRejected = true
            myConv.unreadCount = 0
            myConv.save()
        }
        log.info("【rejectConversation】拒绝成功, convId={}", myConv.id)

        return success()
    }

    /**
     * 向指定组织发送群发消息
     * 校验管理员权限（超级管理员可发任意组织，其他管理员限管辖范围），支持定向发送给部分成员
     *
     * @param ctx 上下文，包含用户身份和数据库操作
     * @param organizationId 目标组织ID
     * @param content 消息内容
     * @param messageType 消息类型，默认为 1（文本消息）
     * @param userIds 可选的接收者用户ID列表，为 null 时发送给组织内所有成员
     * @return 操作结果，成功返回 success()
     * @throws ErrorResponseException 当无管理员权限或组织不存在时抛出 403/404 错误
     */
    suspend fun broadcastToOrganization(ctx: Context, organizationId: String, content: String, messageType: Int = 1, userIds: List<String>? = null): Any = ctx {
        val orgId = organizationId.toUuid()
        val currentUserId = ctx.userId.toUuid()

        val myRoleType = ctx.query<QViewUserRoleEntity> {
            userId.eq(currentUserId)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"

        if (myRoleType !in listOf("super_admin", "admin", "school_admin", "college_admin", "major_admin", "class_admin")) {
            // 检查是否有管理员分配
            val hasAdminAssignment = ctx.query<QAlkSchoolAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty() ||
                ctx.query<QAlkCollegeAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty() ||
                ctx.query<QAlkMajorAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty() ||
                ctx.query<QAlkClassAdmin> { userId.eq(currentUserId); deleted.ne(true) }.toAsync().findList().isNotEmpty()
            if (!hasAdminAssignment) {
                throw ErrorResponseException(HttpStatus.FORBIDDEN, "仅管理员可以群发消息")
            }
        }

        // 超级管理员可以发送到任何组织
        if (myRoleType == "super_admin") {
            // 跳过权限校验
        } else {
            val org = ctx.query<QAlkOrganization>()
                .id.eq(orgId)
                .deleted.ne(true)
                .toAsync().findOne() ?: throw ErrorResponseException(HttpStatus.NOT_FOUND, "组织不存在")

            // 获取当前用户的管理员分配
            val adminAssignments = mutableMapOf<String, Set<UUID>>()
            adminAssignments["school"] = ctx.query<QAlkSchoolAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.schoolId }.toSet()

            adminAssignments["college"] = ctx.query<QAlkCollegeAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.collegeId }.toSet()

            adminAssignments["major"] = ctx.query<QAlkMajorAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.majorId }.toSet()

            adminAssignments["class"] = ctx.query<QAlkClassAdmin> {
                this.userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.classId }.toSet()

            // 判断组织层级并检查权限
            val hasPermission = when {
                // 校级管理员：只能群发自己管理的学校下的组织
                org.classId == null && org.majorId == null && org.collegeId == null -> {
                    org.schoolId != null && adminAssignments["school"]?.contains(org.schoolId) == true
                }
                // 院级管理员：只能群发自己管理的学院下的组织
                org.classId == null && org.majorId == null -> {
                    org.collegeId != null && adminAssignments["college"]?.contains(org.collegeId) == true
                }
                // 专业级管理员：只能群发自己管理的专业下的组织
                org.classId == null -> {
                    org.majorId != null && adminAssignments["major"]?.contains(org.majorId) == true
                }
                // 班级级管理员：只能群发自己管理的班级
                else -> {
                    org.classId != null && adminAssignments["class"]?.contains(org.classId) == true
                }
            }

            if (!hasPermission) {
                throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有权限向该组织发送群发消息")
            }
        }

        val membersQuery = ctx.query<QAlkOrganizationMember>()
            .organizationId.eq(orgId)
            .status.eq(1)
            .deleted.ne(true)

        if (!userIds.isNullOrEmpty()) {
            membersQuery.userId.isIn(userIds.map { it.toUuid() })
        }

        val members = membersQuery.toAsync().findList()

        if (members.isEmpty()) {
            return success()
        }

        withContext(Dispatchers.IO) {
            members.forEach { member ->
                sendBroadcastMessage(ctx, currentUserId, orgId, member.userId, content, messageType)
            }
        }

        return success()
    }

    /**
     * 获取当前用户管理的组织概览信息（用于前端展示群发范围）
     */
    suspend fun getAdminOrgOverview(ctx: Context): Bundle = ctx {
        val currentUserId = ctx.userId.toUuid()

        // 先获取当前用户的管理员分配（用于权限判断）
        val schoolIds = ctx.query<QAlkSchoolAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.schoolId }.toSet()

        val collegeIds = ctx.query<QAlkCollegeAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.collegeId }.toSet()

        val majorIds = ctx.query<QAlkMajorAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.majorId }.toSet()

        val classIds = ctx.query<QAlkClassAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.classId }.toSet()

        val myRoleType = ctx.query<QViewUserRoleEntity> {
            userId.eq(currentUserId)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"

        val hasAnyAdminAssignment = schoolIds.isNotEmpty() || collegeIds.isNotEmpty() || majorIds.isNotEmpty() || classIds.isNotEmpty()
        if (myRoleType !in listOf("super_admin", "admin", "school_admin", "college_admin", "major_admin", "class_admin") && !hasAnyAdminAssignment) {
            return@ctx bundleScope {}
        }

        // 确定管理员层级名称：优先使用角色类型，fallback 到管理员分配判断
        val adminLevel = when (myRoleType) {
            "super_admin" -> "超级管理员"
            "admin" -> "管理员"
            "school_admin" -> "校级管理员"
            "college_admin" -> "院级管理员"
            "major_admin" -> "专业管理员"
            "class_admin" -> "班级管理员"
            else -> when {
                schoolIds.isNotEmpty() -> "校级管理员"
                collegeIds.isNotEmpty() -> "院级管理员"
                majorIds.isNotEmpty() -> "专业管理员"
                classIds.isNotEmpty() -> "班级管理员"
                else -> "普通成员"
            }
        }

        val result = bundleScope {
            "roleType" set myRoleType
            "adminLevel" set adminLevel
        }

        // 按角色类型判断查询范围（与 broadcastByAdminLevel 保持一致，避免多层级分配时走错分支）
        // 各层级统计逻辑抽成局部函数，else 分支按最低层级分配复用
        suspend fun statsForSchools(): Int {
            val schools = ctx.query<QAlkSchool> {
                id.isIn(schoolIds)
            }.toAsync().findList()

            val orgs = ctx.query<QAlkOrganization>()
                .schoolId.isIn(schoolIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()

            val orgIds = orgs.map { it.id }.toSet()
            val totalMembers = ctx.query<QAlkOrganizationMember>()
                .organizationId.isIn(orgIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()
                .distinctBy { it.userId }
                .size

            result["orgs"] = schools.map { school ->
                val schoolOrgs = orgs.filter { it.schoolId == school.id }
                val schoolOrgIds = schoolOrgs.map { it.id }.toSet()
                val schoolMembers = ctx.query<QAlkOrganizationMember>()
                    .organizationId.isIn(schoolOrgIds)
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()
                    .distinctBy { it.userId }
                    .size

                bundleScope {
                    "name" set school.name
                    "memberCount" set schoolMembers
                    "orgCount" set schoolOrgs.size
                }
            }
            return totalMembers
        }

        suspend fun statsForColleges(): Int {
            val colleges = ctx.query<QAlkCollege> {
                id.isIn(collegeIds)
            }.toAsync().findList()

            val orgs = ctx.query<QAlkOrganization>()
                .collegeId.isIn(collegeIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()

            val orgIds = orgs.map { it.id }.toSet()
            val totalMembers = ctx.query<QAlkOrganizationMember>()
                .organizationId.isIn(orgIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()
                .distinctBy { it.userId }
                .size

            result["orgs"] = colleges.map { college ->
                val collegeOrgs = orgs.filter { it.collegeId == college.id }
                val collegeOrgIds = collegeOrgs.map { it.id }.toSet()
                val collegeMembers = ctx.query<QAlkOrganizationMember>()
                    .organizationId.isIn(collegeOrgIds)
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()
                    .distinctBy { it.userId }
                    .size

                bundleScope {
                    "name" set college.name
                    "memberCount" set collegeMembers
                    "orgCount" set collegeOrgs.size
                }
            }
            return totalMembers
        }

        suspend fun statsForMajors(): Int {
            val majors = ctx.query<QAlkMajor> {
                id.isIn(majorIds)
            }.toAsync().findList()

            val orgs = ctx.query<QAlkOrganization>()
                .majorId.isIn(majorIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()

            val orgIds = orgs.map { it.id }.toSet()
            val totalMembers = ctx.query<QAlkOrganizationMember>()
                .organizationId.isIn(orgIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()
                .distinctBy { it.userId }
                .size

            result["orgs"] = majors.map { major ->
                val majorOrgs = orgs.filter { it.majorId == major.id }
                val majorOrgIds = majorOrgs.map { it.id }.toSet()
                val majorMembers = ctx.query<QAlkOrganizationMember>()
                    .organizationId.isIn(majorOrgIds)
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()
                    .distinctBy { it.userId }
                    .size

                bundleScope {
                    "name" set major.name
                    "memberCount" set majorMembers
                    "orgCount" set majorOrgs.size
                }
            }
            return totalMembers
        }

        suspend fun statsForClasses(): Int {
            val classes = ctx.query<QAlkClass> {
                id.isIn(classIds)
            }.toAsync().findList()

            val orgs = ctx.query<QAlkOrganization>()
                .classId.isIn(classIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()

            val orgIds = orgs.map { it.id }.toSet()
            val totalMembers = ctx.query<QAlkOrganizationMember>()
                .organizationId.isIn(orgIds)
                .status.eq(1)
                .deleted.ne(true)
                .toAsync().findList()
                .distinctBy { it.userId }
                .size

            result["orgs"] = classes.map { clazz ->
                val classOrgs = orgs.filter { it.classId == clazz.id }
                val classOrgIds = classOrgs.map { it.id }.toSet()
                val classMembers = ctx.query<QAlkOrganizationMember>()
                    .organizationId.isIn(classOrgIds)
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()
                    .distinctBy { it.userId }
                    .size

                bundleScope {
                    "name" set clazz.name
                    "memberCount" set classMembers
                    "orgCount" set classOrgs.size
                }
            }
            return totalMembers
        }

        when (myRoleType) {
            "super_admin", "admin" -> {
                // 超级管理员/管理员：统计所有组织
                val totalOrgs = ctx.query<QAlkOrganization>()
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()

                val totalMembers = ctx.query<QAlkOrganizationMember>()
                    .organizationId.isIn(totalOrgs.map { it.id }.toSet())
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()
                    .distinctBy { it.userId }
                    .size

                result["orgs"] = listOf(bundleScope {
                    "name" set "全部组织"
                    "memberCount" set totalMembers
                    "orgCount" set totalOrgs.size
                })
            }
            "school_admin" -> {
                if (schoolIds.isEmpty()) return@ctx result
                result["totalMemberCount"] = statsForSchools()
            }
            "college_admin" -> {
                if (collegeIds.isEmpty()) return@ctx result
                result["totalMemberCount"] = statsForColleges()
            }
            "major_admin" -> {
                if (majorIds.isEmpty()) return@ctx result
                result["totalMemberCount"] = statsForMajors()
            }
            "class_admin" -> {
                if (classIds.isEmpty()) return@ctx result
                result["totalMemberCount"] = statsForClasses()
            }
            else -> {
                // 有管理员分配但角色类型不匹配，按最低层级分配处理（与 broadcastByAdminLevel 一致）
                val total = when {
                    classIds.isNotEmpty() -> statsForClasses()
                    majorIds.isNotEmpty() -> statsForMajors()
                    collegeIds.isNotEmpty() -> statsForColleges()
                    schoolIds.isNotEmpty() -> statsForSchools()
                    else -> null
                }
                if (total != null) {
                    result["totalMemberCount"] = total
                }
            }
        }

        return@ctx result
    }

    /**
     * 根据管理员层级群发消息
     * 校级管理员 → 发送给该学校下的全部人员
     * 院级管理员 → 发送给该学院下的全部人员
     * 专业管理员 → 发送给该专业下的全部人员
     * 班级管理员 → 发送给该班级下的全部人员
     * 超级管理员 → 发送给所有组织的全部人员
     */
    suspend fun broadcastByAdminLevel(ctx: Context, content: String, messageType: Int = 1): Any = ctx {
        val currentUserId = ctx.userId.toUuid()
        log.info("【broadcastByAdminLevel】STEP1: 开始群发消息, userId={}, content={}", currentUserId, content.take(30))

        // 先获取当前用户的管理员分配（用于权限判断和范围确定）
        val schoolIds = ctx.query<QAlkSchoolAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.schoolId }.toSet()

        val collegeIds = ctx.query<QAlkCollegeAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.collegeId }.toSet()

        val majorIds = ctx.query<QAlkMajorAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.majorId }.toSet()

        val classIds = ctx.query<QAlkClassAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.classId }.toSet()

        val myRoleType = ctx.query<QViewUserRoleEntity> {
            userId.eq(currentUserId)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"

        log.info("【broadcastByAdminLevel】STEP2: 管理员分配, role={}, schoolIds={}, collegeIds={}, majorIds={}, classIds={}",
            myRoleType, schoolIds.size, collegeIds.size, majorIds.size, classIds.size)

        val hasAnyAdminAssignment = schoolIds.isNotEmpty() || collegeIds.isNotEmpty() || majorIds.isNotEmpty() || classIds.isNotEmpty()
        if (myRoleType !in listOf("super_admin", "admin", "school_admin", "college_admin", "major_admin", "class_admin") && !hasAnyAdminAssignment) {
            log.warn("【broadcastByAdminLevel】STEP2: 权限不足，不是管理员也没有分配, role={}", myRoleType)
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "仅管理员可以群发消息")
        }

        // 根据管理员层级查询对应的组织（按角色类型判断，避免多层级分配时走错分支）
        val orgQuery = ctx.query<QAlkOrganization>()
            .status.eq(1)
            .deleted.ne(true)

        when (myRoleType) {
            "super_admin", "admin" -> {
                // 超级管理员/管理员：发送给所有组织
                log.info("【broadcastByAdminLevel】STEP3: 角色为{}, 发送给所有组织", myRoleType)
            }
            "school_admin" -> {
                if (schoolIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何学校")
                orgQuery.schoolId.isIn(schoolIds)
                log.info("【broadcastByAdminLevel】STEP3: 校级管理员, schoolIds={}", schoolIds)
            }
            "college_admin" -> {
                if (collegeIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何学院")
                orgQuery.collegeId.isIn(collegeIds)
                log.info("【broadcastByAdminLevel】STEP3: 院级管理员, collegeIds={}", collegeIds)
            }
            "major_admin" -> {
                if (majorIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何专业")
                orgQuery.majorId.isIn(majorIds)
                log.info("【broadcastByAdminLevel】STEP3: 专业管理员, majorIds={}", majorIds)
            }
            "class_admin" -> {
                if (classIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何班级")
                orgQuery.classId.isIn(classIds)
                log.info("【broadcastByAdminLevel】STEP3: 班级管理员, classIds={}", classIds)
            }
            else -> {
                // 有管理员分配但角色类型不匹配，按最低层级分配处理
                when {
                    classIds.isNotEmpty() -> {
                        orgQuery.classId.isIn(classIds)
                        log.info("【broadcastByAdminLevel】STEP3: fallback到班级管理员, classIds={}", classIds)
                    }
                    majorIds.isNotEmpty() -> {
                        orgQuery.majorId.isIn(majorIds)
                        log.info("【broadcastByAdminLevel】STEP3: fallback到专业管理员, majorIds={}", majorIds)
                    }
                    collegeIds.isNotEmpty() -> {
                        orgQuery.collegeId.isIn(collegeIds)
                        log.info("【broadcastByAdminLevel】STEP3: fallback到院级管理员, collegeIds={}", collegeIds)
                    }
                    schoolIds.isNotEmpty() -> {
                        orgQuery.schoolId.isIn(schoolIds)
                        log.info("【broadcastByAdminLevel】STEP3: fallback到校级管理员, schoolIds={}", schoolIds)
                    }
                    else -> throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何组织层级")
                }
            }
        }

        val organizations = orgQuery.toAsync().findList()
        if (organizations.isEmpty()) {
            log.warn("【broadcastByAdminLevel】STEP4: 未找到组织，直接返回成功")
            return success()
        }
        log.info("【broadcastByAdminLevel】STEP4: 找到{}个组织", organizations.size)

        // 收集所有唯一用户
        val allOrgIds = organizations.map { it.id }.toSet()
        val members = ctx.query<QAlkOrganizationMember>()
            .organizationId.isIn(allOrgIds)
            .status.eq(1)
            .deleted.ne(true)
            .toAsync().findList()
            .distinctBy { it.userId }

        log.info("【broadcastByAdminLevel】STEP5: 找到{}个组织的{}个成员（去重后）", organizations.size, members.size)

        // 为每个接收者创建会话和消息记录，并推送通知
        // 使用第一个组织的ID作为代表（实际查询时通过senderId关联）
        val representativeOrgId = organizations.firstOrNull()?.id ?: return success()

        // 使用 withContext(Dispatchers.IO) 确保在IO线程中保存数据
        // 保存完成后，数据已经在数据库中，可以被后续查询到
        withContext(Dispatchers.IO) {
            members.forEachIndexed { index, member ->
                sendBroadcastMessage(ctx, currentUserId, representativeOrgId, member.userId, content, 5)
                if (index == 0) {
                    log.info("【broadcastByAdminLevel】STEP6: 第一条消息已发送给 receiver={}", member.userId)
                }
            }
            log.info("【broadcastByAdminLevel】STEP6: 全部{}条消息发送完成", members.size)
            
            // 在相同的IO线程中验证消息是否已保存
            val savedCount = ctx.query<QAlkMessage>()
                .senderId.eq(currentUserId)
                .messageType.eq(5)
                .deleted.ne(true)
                .toAsync().findList().size
            log.info("【broadcastByAdminLevel】STEP7: 验证结果 - 数据库中已保存{}条群发消息", savedCount)
        }

        // 短暂等待，确保事务提交完成
        // 这是为了防止前端立即查询时事务还未提交
        delay(100)

        log.info("【broadcastByAdminLevel】STEP8: 群发完成, 共发送{}条消息", members.size)

        return success()
    }

    /**
     * 发送单条群发消息给指定用户
     * 每个用户对每个管理员只有一个广播会话，旧会话保留作为历史
     */
    private suspend fun sendBroadcastMessage(ctx: Context, senderId: UUID, organizationId: UUID, receiverId: UUID, content: String, messageType: Int) {
        log.info("【sendBroadcastMessage】A: 开始发送单条群发, sender={}, receiver={}", senderId, receiverId)

        // 查找或创建 (receiverId, senderId) 会话——每个用户每个管理员只有一个会话
        var broadcastConv = ctx.query<QAlkConversation>()
            .userId.eq(receiverId)
            .targetUserId.eq(senderId)
            .toAsync().findList().firstOrNull()

        if (broadcastConv == null) {
            log.info("【sendBroadcastMessage】B: 未找到会话，创建新会话, receiver={}, sender={}", receiverId, senderId)
            broadcastConv = ctx.createModel<AlkConversation> {
                this.userId = receiverId
                this.targetUserId = senderId
                this.createdBy = ctx.userIdOrNull ?: ctx.user.id
            }
            broadcastConv.save()
            log.info("【sendBroadcastMessage】B: 新会话创建成功, convId={}", broadcastConv.id)
        } else {
            log.info("【sendBroadcastMessage】B: 使用现有会话, convId={}", broadcastConv.id)
        }

        // 创建新消息（旧消息保留在各自会话中作为历史）
        val message = ctx.createModel<AlkMessage> {
            this.conversationId = broadcastConv.id
            this.organizationId = organizationId
            this.senderId = senderId
            this.receiverId = receiverId
            this.content = content
            this.messageType = 5
            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
        }
        message.save()
        log.info("【sendBroadcastMessage】C: 消息保存成功, msgId={}, receiver={}", message.id, receiverId)

        // 更新会话最后消息
        broadcastConv.lastMessage = content.take(50)
        broadcastConv.save()
        log.info("【sendBroadcastMessage】D: 会话最后消息更新完成")

        // 推送通知
        log.info("【sendBroadcastMessage】E: 开始SSE推送, receiver={}, event=broadcast:received", receiverId)
        broadcastManager.broadcastToUser("broadcast:received", mapOf(
            "senderId" to senderId.toString(),
            "content" to content,
            "messageType" to 5,
            "createdAt" to message.createdAt?.toString()
        ), receiverId.toString())
        log.info("【sendBroadcastMessage】F: SSE推送完成, msgId={}, receiver={}", message.id, receiverId)
    }

    // ==================== 群聊相关方法 ====================

    /**
     * 查找或创建群聊会话
     * 按 scopeType + scopeId 唯一确定一个群聊
     */
    private suspend fun getOrCreateGroupConversation(
        ctx: Context,
        scopeType: String,
        scopeId: UUID,
        creatorId: UUID
    ): AlkGroupConversation = withContext(Dispatchers.IO) {
        var groupConv = ctx.query<QAlkGroupConversation>()
            .scopeType.eq(scopeType)
            .scopeId.eq(scopeId)
            .deleted.ne(true)
            .orderBy().createdAt.desc()
            .toAsync().findList().firstOrNull()

        if (groupConv == null) {
            val scopeName = when (scopeType) {
                "school" -> ctx.query<QAlkSchool>().id.eq(scopeId).toAsync().findList().firstOrNull()?.name ?: "学校"
                "college" -> ctx.query<QAlkCollege>().id.eq(scopeId).toAsync().findList().firstOrNull()?.name ?: "学院"
                "major" -> ctx.query<QAlkMajor>().id.eq(scopeId).toAsync().findList().firstOrNull()?.name ?: "专业"
                "class" -> ctx.query<QAlkClass>().id.eq(scopeId).toAsync().findList().firstOrNull()?.name ?: "班级"
                else -> "组织"
            }

            groupConv = ctx.createModel<AlkGroupConversation> {
                this.groupName = "${scopeName}通知"
                this.scopeType = scopeType
                this.scopeId = scopeId
                this.creatorId = creatorId
            }
            groupConv.save()
        }
        groupConv
    }

    /**
     * 添加群聊成员
     */
    private suspend fun addGroupMembers(
        ctx: Context,
        groupId: UUID,
        memberIds: List<UUID>,
        adminUserIds: Set<UUID> = emptySet()
    ) = withContext(Dispatchers.IO) {
        memberIds.forEach { userId ->
            val isAdmin = userId in adminUserIds
            var member = ctx.query<QAlkGroupConversationMember>()
                .groupConversationId.eq(groupId)
                .userId.eq(userId)
                .deleted.ne(true)
                .toAsync().findList().firstOrNull()

            if (member == null) {
                member = ctx.createModel<AlkGroupConversationMember> {
                    this.groupConversationId = groupId
                    this.userId = userId
                    this.isAdmin = isAdmin
                }
                member.save()
            } else if (member.isAdmin != isAdmin) {
                member.isAdmin = isAdmin
                member.save()
            }
        }

        // 更新群成员数量
        val memberCount = ctx.query<QAlkGroupConversationMember>()
            .groupConversationId.eq(groupId)
            .deleted.ne(true)
            .toAsync().findList().size
        val groupConv = ctx.query<QAlkGroupConversation>().id.eq(groupId).toAsync().findList().firstOrNull()
        if (groupConv != null) {
            groupConv.memberCount = memberCount
            groupConv.save()
        }
    }

    /**
     * 获取指定范围的同级管理员（相同角色类型 + 相同管理范围）
     */
    private suspend fun getSameLevelAdmins(
        ctx: Context,
        scopeType: String,
        scopeId: UUID,
        currentUserId: UUID
    ): Set<UUID> = withContext(Dispatchers.IO) {
        val adminIds = when (scopeType) {
            "school" -> {
                ctx.query<QAlkSchoolAdmin>()
                    .schoolId.eq(scopeId)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.userId }.toSet()
            }
            "college" -> {
                ctx.query<QAlkCollegeAdmin>()
                    .collegeId.eq(scopeId)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.userId }.toSet()
            }
            "major" -> {
                ctx.query<QAlkMajorAdmin>()
                    .majorId.eq(scopeId)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.userId }.toSet()
            }
            "class" -> {
                ctx.query<QAlkClassAdmin>()
                    .classId.eq(scopeId)
                    .deleted.ne(true)
                    .toAsync().findList().map { it.userId }.toSet()
            }
            else -> emptySet()
        }
        adminIds + currentUserId // 包含自己
    }

    /**
     * 判断两个用户是否为同级管理员（相同角色类型 + 相同管理范围）
     */
    private suspend fun isSameLevelAdmin(
        ctx: Context,
        userId1: UUID,
        userId2: UUID
    ): Boolean = withContext(Dispatchers.IO) {
        if (userId1 == userId2) return@withContext true

        // 检查学校管理员
        val schoolAdmins1 = ctx.query<QAlkSchoolAdmin>()
            .userId.eq(userId1).deleted.ne(true).toAsync().findList().map { it.schoolId }.toSet()
        val schoolAdmins2 = ctx.query<QAlkSchoolAdmin>()
            .userId.eq(userId2).deleted.ne(true).toAsync().findList().map { it.schoolId }.toSet()
        if (schoolAdmins1.isNotEmpty() && schoolAdmins1.intersect(schoolAdmins2).isNotEmpty()) return@withContext true

        // 检查学院管理员
        val collegeAdmins1 = ctx.query<QAlkCollegeAdmin>()
            .userId.eq(userId1).deleted.ne(true).toAsync().findList().map { it.collegeId }.toSet()
        val collegeAdmins2 = ctx.query<QAlkCollegeAdmin>()
            .userId.eq(userId2).deleted.ne(true).toAsync().findList().map { it.collegeId }.toSet()
        if (collegeAdmins1.isNotEmpty() && collegeAdmins1.intersect(collegeAdmins2).isNotEmpty()) return@withContext true

        // 检查专业管理员
        val majorAdmins1 = ctx.query<QAlkMajorAdmin>()
            .userId.eq(userId1).deleted.ne(true).toAsync().findList().map { it.majorId }.toSet()
        val majorAdmins2 = ctx.query<QAlkMajorAdmin>()
            .userId.eq(userId2).deleted.ne(true).toAsync().findList().map { it.majorId }.toSet()
        if (majorAdmins1.isNotEmpty() && majorAdmins1.intersect(majorAdmins2).isNotEmpty()) return@withContext true

        // 检查班级管理员
        val classAdmins1 = ctx.query<QAlkClassAdmin>()
            .userId.eq(userId1).deleted.ne(true).toAsync().findList().map { it.classId }.toSet()
        val classAdmins2 = ctx.query<QAlkClassAdmin>()
            .userId.eq(userId2).deleted.ne(true).toAsync().findList().map { it.classId }.toSet()
        if (classAdmins1.isNotEmpty() && classAdmins1.intersect(classAdmins2).isNotEmpty()) return@withContext true

        false
    }

    /**
     * 判断用户是否可以在通知（群聊）中发送消息
     *
     * 层级权限规则（上级管理员可发下级通知）：
     * - school 范围：仅该学校的 school_admin 可发
     * - college 范围：该学校的 school_admin + 该学院的 college_admin 可发
     * - major 范围：school_admin + college_admin + major_admin 可发
     * - class 范围：school_admin + college_admin + major_admin + class_admin 可发
     */
    private suspend fun canSendInGroup(
        ctx: Context,
        groupId: UUID,
        userId: UUID
    ): Boolean = withContext(Dispatchers.IO) {
        val groupConv = ctx.query<QAlkGroupConversation>()
            .id.eq(groupId).deleted.ne(true).toAsync().findList().firstOrNull()
            ?: return@withContext false

        // super_admin / admin 可发任何通知
        val roleType = ctx.query<QViewUserRoleEntity>()
            .userId.eq(userId)
            .orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"
        if (roleType == "super_admin" || roleType == "admin") return@withContext true

        val scopeType = groupConv.scopeType ?: return@withContext false
        val scopeId = groupConv.scopeId ?: return@withContext false

        when (scopeType) {
            "school" -> {
                // 仅该学校的 school_admin 可发
                ctx.query<QAlkSchoolAdmin>()
                    .userId.eq(userId).deleted.ne(true)
                    .schoolId.eq(scopeId)
                    .toAsync().findList().isNotEmpty()
            }
            "college" -> {
                // school_admin（该学院所属学校）+ college_admin（该学院）可发
                val college = ctx.query<QAlkCollege>().id.eq(scopeId).toAsync().findList().firstOrNull()
                if (college != null) {
                    val isSchoolAdmin = ctx.query<QAlkSchoolAdmin>()
                        .userId.eq(userId).deleted.ne(true)
                        .schoolId.eq(college.schoolId)
                        .toAsync().findList().isNotEmpty()
                    if (isSchoolAdmin) return@withContext true
                }
                ctx.query<QAlkCollegeAdmin>()
                    .userId.eq(userId).deleted.ne(true)
                    .collegeId.eq(scopeId)
                    .toAsync().findList().isNotEmpty()
            }
            "major" -> {
                // school_admin + college_admin + major_admin 可发
                val major = ctx.query<QAlkMajor>().id.eq(scopeId).toAsync().findList().firstOrNull()
                if (major != null) {
                    if (major.collegeId != null) {
                        val college = ctx.query<QAlkCollege>().id.eq(major.collegeId).toAsync().findList().firstOrNull()
                        if (college != null) {
                            val isSchoolAdmin = ctx.query<QAlkSchoolAdmin>()
                                .userId.eq(userId).deleted.ne(true)
                                .schoolId.eq(college.schoolId)
                                .toAsync().findList().isNotEmpty()
                            if (isSchoolAdmin) return@withContext true
                        }
                        val isCollegeAdmin = ctx.query<QAlkCollegeAdmin>()
                            .userId.eq(userId).deleted.ne(true)
                            .collegeId.eq(major.collegeId)
                            .toAsync().findList().isNotEmpty()
                        if (isCollegeAdmin) return@withContext true
                    }
                }
                ctx.query<QAlkMajorAdmin>()
                    .userId.eq(userId).deleted.ne(true)
                    .majorId.eq(scopeId)
                    .toAsync().findList().isNotEmpty()
            }
            "class" -> {
                // school_admin + college_admin + major_admin + class_admin 可发
                val klass = ctx.query<QAlkClass>().id.eq(scopeId).toAsync().findList().firstOrNull()
                if (klass != null) {
                    if (klass.majorId != null) {
                        val major = ctx.query<QAlkMajor>().id.eq(klass.majorId).toAsync().findList().firstOrNull()
                        if (major != null) {
                            if (major.collegeId != null) {
                                val college = ctx.query<QAlkCollege>().id.eq(major.collegeId).toAsync().findList().firstOrNull()
                                if (college != null) {
                                    val isSchoolAdmin = ctx.query<QAlkSchoolAdmin>()
                                        .userId.eq(userId).deleted.ne(true)
                                        .schoolId.eq(college.schoolId)
                                        .toAsync().findList().isNotEmpty()
                                    if (isSchoolAdmin) return@withContext true
                                }
                                val isCollegeAdmin = ctx.query<QAlkCollegeAdmin>()
                                    .userId.eq(userId).deleted.ne(true)
                                    .collegeId.eq(major.collegeId)
                                    .toAsync().findList().isNotEmpty()
                                if (isCollegeAdmin) return@withContext true
                            }
                            val isMajorAdmin = ctx.query<QAlkMajorAdmin>()
                                .userId.eq(userId).deleted.ne(true)
                                .majorId.eq(klass.majorId)
                                .toAsync().findList().isNotEmpty()
                            if (isMajorAdmin) return@withContext true
                        }
                    }
                }
                ctx.query<QAlkClassAdmin>()
                    .userId.eq(userId).deleted.ne(true)
                    .classId.eq(scopeId)
                    .toAsync().findList().isNotEmpty()
            }
            else -> false
        }
    }

    /**
     * 发送群聊消息
     */
    private suspend fun sendGroupMessageInternal(
        ctx: Context,
        groupId: UUID,
        senderId: UUID,
        content: String,
        messageType: Int = 6
    ) = withContext(Dispatchers.IO) {
        log.info("【sendGroupMessageInternal】A: 开始发送群聊消息, groupId={}, sender={}", groupId, senderId)

        // 获取群成员列表
        val members = ctx.query<QAlkGroupConversationMember>()
            .groupConversationId.eq(groupId)
            .deleted.ne(true)
            .toAsync().findList()
        log.info("【sendGroupMessageInternal】B: 群成员共{}人", members.size)

        if (members.isEmpty()) {
            log.warn("【sendGroupMessageInternal】B: 群成员为空，直接返回")
            return@withContext
        }

        // 查找或创建发送者的会话
        var conversation = ctx.query<QAlkConversation>()
            .userId.eq(senderId)
            .targetUserId.eq(groupId)
            .orderBy().updatedAt.desc()
            .toAsync().findList().firstOrNull()

        if (conversation == null) {
            log.info("【sendGroupMessageInternal】C: 未找到会话，创建新会话")
            conversation = ctx.createModel<AlkConversation> {
                this.userId = senderId
                this.targetUserId = groupId
                this.createdBy = ctx.userIdOrNull ?: ctx.user.id
            }
            conversation.save()
            log.info("【sendGroupMessageInternal】C: 新会话创建成功, convId={}", conversation.id)
        } else {
            log.info("【sendGroupMessageInternal】C: 使用现有会话, convId={}", conversation.id)
        }

        // 创建群聊消息
        val message = ctx.createModel<AlkMessage> {
            this.conversationId = conversation.id
            this.groupConversationId = groupId
            this.senderId = senderId
            this.content = content
            this.messageType = messageType
        }
        message.save()
        log.info("【sendGroupMessageInternal】D: 消息保存成功, msgId={}", message.id)

        // 更新群聊最后消息
        val groupConv = ctx.query<QAlkGroupConversation>()
            .id.eq(groupId).toAsync().findList().firstOrNull()
        if (groupConv != null) {
            groupConv.lastMessage = content.take(50)
            groupConv.lastMessageAt = Date()
            groupConv.save()
            log.info("【sendGroupMessageInternal】E: 群聊最后消息更新成功")
        }

        // 更新会话最后消息
        conversation.lastMessage = content.take(50)
        conversation.save()
        log.info("【sendGroupMessageInternal】F: 会话最后消息更新成功")

        // 重置所有成员的未读计数（除了发送者）
        members.forEach { member ->
            if (member.userId != senderId) {
                member.unreadCount = member.unreadCount + 1
                member.save()
            }
        }
        log.info("【sendGroupMessageInternal】G: 其他成员的未读计数已更新")

        // 推送通知给所有群成员
        val nonSenderMembers = members.filter { it.userId != senderId }
        log.info("【sendGroupMessageInternal】H: 开始SSE推送, 目标{}人, event=group:message", nonSenderMembers.size)
        nonSenderMembers.forEach { member ->
            broadcastManager.broadcastToUser("group:message", mapOf(
                "groupId" to groupId.toString(),
                "senderId" to senderId.toString(),
                "content" to content,
                "messageType" to messageType,
                "createdAt" to message.createdAt?.toString()
            ), member.userId.toString())
        }
        log.info("【sendGroupMessageInternal】I: 全部SSE推送完成")
    }

    /**
     * 获取用户可见的群聊列表
     */
    suspend fun getMyGroupConversations(ctx: Context): List<Bundle> = withContext(Dispatchers.IO) {
        val currentUserId = ctx.userId.toUuid()

        // 查询用户所在的所有群聊
        val memberships = ctx.query<QAlkGroupConversationMember>()
            .userId.eq(currentUserId)
            .deleted.ne(true)
            .toAsync().findList()

        if (memberships.isEmpty()) {
            return@withContext emptyList()
        }

        val groupIds = memberships.map { it.groupConversationId }.toSet()
        val groups = ctx.query<QAlkGroupConversation>()
            .id.`in`(groupIds)
            .deleted.ne(true)
            .orderBy().lastMessageAt.desc()
            .toAsync().findList()

        groups.map { group ->
            val member = memberships.find { it.groupConversationId == group.id }
            // 使用层级权限判断是否可发送通知
            val canSend = canSendInGroup(ctx, group.id, currentUserId)
            bundleScope {
                "id" set group.id
                "groupName" set (group.groupName ?: "通知")
                "scopeType" set group.scopeType
                "scopeId" set group.scopeId
                "creatorId" set group.creatorId
                "lastMessage" set (group.lastMessage ?: "")
                "lastMessageAt" set (group.lastMessageAt?.toString() ?: "")
                "memberCount" set group.memberCount
                "isAdmin" set canSend
                "canSend" set canSend
                "unreadCount" set (member?.unreadCount ?: 0)
                "isTop" set (member?.isTop ?: false)
                "isMuted" set (member?.isMuted ?: false)
            }
        }
    }

    /**
     * 获取群聊消息列表
     */
    suspend fun getGroupMessages(ctx: Context, groupId: String): Bundle = withContext(Dispatchers.IO) {
        val currentUserId = ctx.userId.toUuid()
        val gid = groupId.toUuid()

        // 检查是否为群成员
        val member = ctx.query<QAlkGroupConversationMember>()
            .groupConversationId.eq(gid)
            .userId.eq(currentUserId)
            .deleted.ne(true)
            .toAsync().findList().firstOrNull()

        if (member == null) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "您不是该群聊的成员")
        }

        // 获取群聊消息
        val messages = ctx.query<QAlkMessage>()
            .groupConversationId.eq(gid)
            .deleted.ne(true)
            .orderBy().createdAt.asc()
            .toAsync().findList()

        val result = messages.map { msg ->
            bundleScope {
                "id" set msg.id
                "senderId" set msg.senderId
                "content" set msg.content
                "messageType" set msg.messageType
                "createdAt" set msg.createdAt?.toString()
                "isRead" set msg.isRead
                "isRecalled" set msg.isRecalled
            }
        }

        // 重置未读计数
        member.unreadCount = 0
        member.lastReadAt = Date()
        member.save()

        bundleScope {
            "messages" set result
        }
    }

    /**
     * 发送消息到群聊
     */
    suspend fun sendGroupMessage(ctx: Context, groupId: String, content: String): Bundle = withContext(Dispatchers.IO) {
        val currentUserId = ctx.userId.toUuid()
        val gid = groupId.toUuid()

        // 检查权限
        val canSend = canSendInGroup(ctx, gid, currentUserId)
        if (!canSend) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有权限在该通知中发送消息")
        }

        // 发送消息
        sendGroupMessageInternal(ctx, gid, currentUserId, content)

        bundleScope {
            "success" set true
        }
    }

    /**
     * 重写群发方法：创建群聊模式
     */
    suspend fun broadcastToGroup(ctx: Context, content: String): Any = ctx {
        val currentUserId = ctx.userId.toUuid()
        log.info("broadcastToGroup: Starting, userId={}, contentLength={}", currentUserId, content.length)

        // 获取当前用户的管理员分配
        val schoolIds = ctx.query<QAlkSchoolAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.schoolId }.toSet()

        val collegeIds = ctx.query<QAlkCollegeAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.collegeId }.toSet()

        val majorIds = ctx.query<QAlkMajorAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.majorId }.toSet()

        val classIds = ctx.query<QAlkClassAdmin> {
            this.userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.classId }.toSet()

        val myRoleType = ctx.query<QViewUserRoleEntity> {
            userId.eq(currentUserId)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"

        val hasAnyAdminAssignment = schoolIds.isNotEmpty() || collegeIds.isNotEmpty() || majorIds.isNotEmpty() || classIds.isNotEmpty()
        if (myRoleType !in listOf("super_admin", "admin", "school_admin", "college_admin", "major_admin", "class_admin") && !hasAnyAdminAssignment) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "仅管理员可以群发消息")
        }

        // 确定范围类型和范围ID
        val (scopeType, scopeIds) = when (myRoleType) {
            "super_admin", "admin" -> {
                val allSchoolIds = ctx.query<QAlkSchool>().deleted.ne(true).toAsync().findList().map { it.id }.toSet()
                "school" to allSchoolIds
            }
            "school_admin" -> {
                if (schoolIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何学校")
                "school" to schoolIds
            }
            "college_admin" -> {
                if (collegeIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何学院")
                "college" to collegeIds
            }
            "major_admin" -> {
                if (majorIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何专业")
                "major" to majorIds
            }
            "class_admin" -> {
                if (classIds.isEmpty()) throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何班级")
                "class" to classIds
            }
            else -> {
                val (type, ids) = when {
                    classIds.isNotEmpty() -> "class" to classIds
                    majorIds.isNotEmpty() -> "major" to majorIds
                    collegeIds.isNotEmpty() -> "college" to collegeIds
                    schoolIds.isNotEmpty() -> "school" to schoolIds
                    else -> throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有被分配到任何组织层级")
                }
                type to ids
            }
        }

        // 处理每个范围
        scopeIds.forEach { scopeId ->
            // 1. 查找或创建群聊
            val groupConv = getOrCreateGroupConversation(ctx, scopeType, scopeId, currentUserId)

            // 2. 获取所有成员（管理员 + 被管理用户 + 同级管理员）
            val allMemberIds = mutableSetOf(currentUserId)
            val adminUserIds = mutableSetOf(currentUserId)

            // 获取同级管理员
            val sameLevelAdmins = getSameLevelAdmins(ctx, scopeType, scopeId, currentUserId)
            adminUserIds.addAll(sameLevelAdmins)
            allMemberIds.addAll(sameLevelAdmins)

            // 获取被管理的所有用户
            val orgQuery = ctx.query<QAlkOrganization>()
                .status.eq(1)
                .deleted.ne(true)

            when (scopeType) {
                "school" -> orgQuery.schoolId.eq(scopeId)
                "college" -> orgQuery.collegeId.eq(scopeId)
                "major" -> orgQuery.majorId.eq(scopeId)
                "class" -> orgQuery.classId.eq(scopeId)
            }

            val organizations = orgQuery.toAsync().findList()
            val allOrgIds = organizations.map { it.id }.toSet()

            val members = if (allOrgIds.isNotEmpty()) {
                ctx.query<QAlkOrganizationMember>()
                    .organizationId.isIn(allOrgIds)
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()
                    .distinctBy { it.userId }
                    .map { it.userId }
                    .toSet()
            } else {
                emptySet()
            }

            allMemberIds.addAll(members)

            // 3. 添加所有成员到群聊
            addGroupMembers(ctx, groupConv.id, allMemberIds.toList(), adminUserIds)

            // 4. 发送群聊消息
            sendGroupMessageInternal(ctx, groupConv.id, currentUserId, content)
        }

        log.info("broadcastToGroup: All {} groups processed successfully", scopeIds.size)

        return success()
    }
}