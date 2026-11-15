package com.zxce.ai.model.cultural

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_claim_item")
class AlkClaimItem : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("认领物品标题")
    @Column(nullable = false)
    lateinit var title: String

    @DbComment("物品描述")
    var description: String? = null

    @DbComment("物品图片")
    var image: String? = null

    @DbComment("总数量")
    var quantity: Int = 1

    @DbComment("剩余数量")
    var remaining: Int = 1
}