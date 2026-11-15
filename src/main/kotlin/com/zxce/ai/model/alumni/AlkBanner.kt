package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_banner")
class AlkBanner : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("标题")
    @Column(nullable = false)
    lateinit var title: String

    @DbComment("副标题")
    var subtitle: String? = null

    @DbComment("图片地址（可为空，前端用渐变卡片渲染）")
    var imageUrl: String? = null

    @DbComment("跳转类型: 0-无 1-帖子 2-外链")
    var targetType: Int = 0

    @DbComment("目标ID（帖子ID）")
    var targetId: String? = null

    @DbComment("外链地址")
    var linkUrl: String? = null

    @DbComment("排序值（升序）")
    var sortOrder: Int = 0

    @DbComment("是否启用")
    var isEnabled: Boolean = true
}
