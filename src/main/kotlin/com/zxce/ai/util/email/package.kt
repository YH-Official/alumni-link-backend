package com.zxce.ai.util.email

import com.zxce.ai.util.spring.ApplicationContextProvider
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper

fun ApplicationContextProvider.sendMail(to: String, title: String, html: String){
    val mailSender = applicationContext.getBean(JavaMailSender::class.java)
    val message = mailSender.createMimeMessage()
    val helper = MimeMessageHelper(message, false)
    helper.setFrom("aigc@lolosia.top", "人工智能实验室")
    helper.setTo(to)
    helper.setSubject("$title - 学金智能AI平台")
    helper.setText(html, true)
    mailSender.send(message)
}