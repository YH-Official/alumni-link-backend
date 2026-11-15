package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_class_admin")
class AlkClassAdmin : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("用户ID")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("班级ID")
    @Column(nullable = false)
    lateinit var classId: UUID
}