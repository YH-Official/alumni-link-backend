package com.zxce.ai.controller.alumni

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.service.alumni.MessageService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*

/**
 * 消息控制器
 *
 * 提供校友私信、群聊、群发消息等消息通讯管理功能，支持私聊、群聊、管理员层级群发等多种消息模式。
 */
@HostForward
@RestController
@RequestMapping("/api/alumni/message")
class MessageController {

    @Autowired
    lateinit var messageService: MessageService

    /**
     * 发送私信
     *
     * @param context 当前会话上下文
     * @param data 消息数据（包含接收者、消息内容等信息）
     * @return 消息发送结果
     * HTTP方法: POST
     */
    @PostMapping("/send")
    suspend fun send(context: Context, @RequestBody data: Bundle): Any {
        return messageService.sendMessage(context, data)
    }

    /**
     * 获取私信会话列表
     *
     * @param context 当前会话上下文
     * @return 会话列表
     * HTTP方法: POST
     */
    @PostMapping("/conversations")
    suspend fun conversations(context: Context): List<Bundle> {
        return messageService.getConversations(context)
    }

    /**
     * 获取群发消息会话列表（每个管理员一个会话，与私聊会话独立）
     *
     * @param context 当前会话上下文
     * @return 群发会话列表
     * HTTP方法: POST
     */
    @PostMapping("/broadcastConversations")
    suspend fun broadcastConversations(context: Context): List<Bundle> {
        return messageService.getBroadcastConversations(context)
    }

    data class MessagesParam(
        var targetUserId: String = "",
        var pageNo: Int = 1,
        var pageSize: Int = 20
    )

    /**
     * 获取与指定用户的聊天消息列表
     *
     * @param context 当前会话上下文
     * @param params 包含 targetUserId（目标用户ID）、pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（消息列表）和 total（总数）的分页结果
     * HTTP方法: POST
     */
    @PostMapping("/messages")
    suspend fun messages(context: Context, @RequestBody params: MessagesParam): Bundle {
        val (data, total) = messageService.getMessages(context, params.targetUserId, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    /**
     * 标记与指定用户的消息为已读
     *
     * @param context 当前会话上下文
     * @param params 包含 targetUserId 的参数体
     * @return 标记操作结果
     * HTTP方法: POST
     */
    @PostMapping("/markRead")
    suspend fun markRead(context: Context, @RequestBody params: Bundle): Any {
        return messageService.markAsRead(context, params["targetUserId"].toString())
    }

    /**
     * 拒绝对方的私信申请
     * 将会话标记为已拒绝，对方后续消息会被拦截
     *
     * @param context 当前会话上下文
     * @param params 包含 targetUserId（被拒绝的用户ID）
     * @return 操作结果
     * HTTP方法: POST
     */
    @PostMapping("/reject")
    suspend fun reject(context: Context, @RequestBody params: Bundle): Any {
        return messageService.rejectConversation(context, params["targetUserId"].toString())
    }

    data class BroadcastParam(
        var organizationId: String = "",
        var content: String = "",
        var messageType: Int = 1,
        var userIds: List<String>? = null
    )

    /**
     * 向指定组织群发消息
     *
     * @param context 当前会话上下文
     * @param params 包含 organizationId（组织ID）、content（消息内容）、messageType（消息类型）、userIds（可选，指定用户ID列表）
     * @return 群发操作结果
     * HTTP方法: POST
     */
    @PostMapping("/broadcast")
    suspend fun broadcast(context: Context, @RequestBody params: BroadcastParam): Any {
        return messageService.broadcastToOrganization(context, params.organizationId, params.content, params.messageType, params.userIds)
    }

    data class BroadcastByLevelParam(
        var content: String = "",
        var messageType: Int = 1
    )

    /**
     * 根据管理员层级群发消息
     * 校级管理员 → 发送给该学校下的全部人员
     * 院级管理员 → 发送给该学院下的全部人员
     * 专业管理员 → 发送给该专业下的全部人员
     * 班级管理员 → 发送给该班级下的全部人员
     * 超级管理员 → 发送给所有组织的全部人员
     *
     * @param context 当前会话上下文
     * @param params 包含 content（消息内容）、messageType（消息类型）
     * @return 群发操作结果
     * HTTP方法: POST
     */
    @PostMapping("/broadcast/byLevel")
    suspend fun broadcastByLevel(context: Context, @RequestBody params: BroadcastByLevelParam): Any {
        return messageService.broadcastByAdminLevel(context, params.content, params.messageType)
    }

    /**
     * 获取当前用户管理的组织概览信息（用于前端展示群发范围）
     *
     * @param context 当前会话上下文
     * @return 管理员组织概览信息
     * HTTP方法: POST
     */
    @PostMapping("/broadcast/overview")
    suspend fun broadcastOverview(context: Context): Bundle {
        return messageService.getAdminOrgOverview(context)
    }

    data class BroadcastListParam(
        var organizationId: String? = null
    )

    /**
     * 获取群发消息目标组织列表
     *
     * @param context 当前会话上下文
     * @return 可群发目标组织列表
     * HTTP方法: POST
     */
    @PostMapping("/broadcast/targets")
    suspend fun broadcastTargets(context: Context): List<Bundle> {
        return messageService.getBroadcastTargets(context)
    }

    /**
     * 获取群发消息历史列表
     *
     * @param context 当前会话上下文
     * @param params 包含 organizationId（可选，组织ID筛选）
     * @return 群发消息历史列表
     * HTTP方法: POST
     */
    @PostMapping("/broadcast/list")
    suspend fun broadcastList(context: Context, @RequestBody params: BroadcastListParam): List<Bundle> {
        return messageService.getBroadcastMessages(context, params.organizationId)
    }

    /**
     * 获取当前管理员自己发送的群发消息历史
     *
     * @param context 当前会话上下文
     * @return 当前管理员发送的群发消息历史列表
     * HTTP方法: POST
     */
    @PostMapping("/broadcast/history")
    suspend fun broadcastHistory(context: Context): List<Bundle> {
        return messageService.getMyBroadcastHistory(context)
    }

    /**
     * 获取当前用户的未读消息总数
     *
     * @param context 当前会话上下文
     * @return 包含 count（未读消息数）的结果
     * HTTP方法: POST
     */
    @PostMapping("/unread/count")
    suspend fun unreadCount(context: Context): Bundle {
        val count = messageService.getTotalUnreadCount(context)
        return bundleScope {
            "count" set count
        }
    }

    // ==================== 群聊相关接口 ====================

    /**
     * 群发消息（创建群聊模式）
     *
     * @param context 当前会话上下文
     * @param params 包含 content（消息内容）
     * @return 群发操作结果
     * HTTP方法: POST
     */
    @PostMapping("/broadcast/toGroup")
    suspend fun broadcastToGroup(context: Context, @RequestBody params: BroadcastByLevelParam): Any {
        return messageService.broadcastToGroup(context, params.content)
    }

    /**
     * 获取用户可见的群聊列表
     *
     * @param context 当前会话上下文
     * @return 群聊会话列表
     * HTTP方法: POST
     */
    @PostMapping("/group/conversations")
    suspend fun groupConversations(context: Context): List<Bundle> {
        return messageService.getMyGroupConversations(context)
    }

    data class GroupMessagesParam(
        var groupId: String = ""
    )

    /**
     * 获取群聊消息列表
     *
     * @param context 当前会话上下文
     * @param params 包含 groupId（群聊ID）
     * @return 群聊消息列表
     * HTTP方法: POST
     */
    @PostMapping("/group/messages")
    suspend fun groupMessages(context: Context, @RequestBody params: GroupMessagesParam): Bundle {
        return messageService.getGroupMessages(context, params.groupId)
    }

    data class SendGroupMessageParam(
        var groupId: String = "",
        var content: String = ""
    )

    /**
     * 发送消息到群聊
     *
     * @param context 当前会话上下文
     * @param params 包含 groupId（群聊ID）、content（消息内容）
     * @return 消息发送结果
     * HTTP方法: POST
     */
    @PostMapping("/group/send")
    suspend fun sendGroupMessage(context: Context, @RequestBody params: SendGroupMessageParam): Bundle {
        return messageService.sendGroupMessage(context, params.groupId, params.content)
    }

    data class RecallParam(
        var messageId: String = ""
    )

    /**
     * 撤回消息
     * 仅发送者本人可在发送后 2 分钟内撤回
     *
     * @param context 当前会话上下文
     * @param params 包含 messageId（消息ID）
     * @return 撤回操作结果
     * HTTP方法: POST
     */
    @PostMapping("/recall")
    suspend fun recall(context: Context, @RequestBody params: RecallParam): Any {
        return messageService.recallMessage(context, mutableMapOf("messageId" to params.messageId))
    }
}
