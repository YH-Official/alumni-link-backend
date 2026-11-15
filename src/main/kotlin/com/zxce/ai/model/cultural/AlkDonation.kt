package com.zxce.ai.model.cultural

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.*

@Entity
@Table(name = "alk_donation")
class AlkDonation : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id，捐赠人")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("捐赠类型: money-捐钱, item-捐物")
    @Column(nullable = false)
    lateinit var type: String

    @DbComment("捐赠金额（捐钱时填写）")
    var amount: BigDecimal? = null

    @DbComment("物品名称（捐物时填写）")
    var itemName: String? = null

    @DbComment("物品描述（捐物时填写）")
    var itemDescription: String? = null

    @DbComment("捐赠目标类型: school-指定学校, general-通用")
    var targetType: String? = null

    @DbComment("捐赠目标ID（如学校ID）")
    var targetId: String? = null

    @DbComment("捐赠目标名称")
    var targetName: String? = null

    @DbComment("捐赠寄语")
    var message: String? = null

    @DbComment("是否匿名捐赠")
    @JvmField
    var anonymous: Boolean = false

    @DbComment("状态: pending-待处理, completed-已完成, cancelled-已取消")
    var status: String = "completed"
}