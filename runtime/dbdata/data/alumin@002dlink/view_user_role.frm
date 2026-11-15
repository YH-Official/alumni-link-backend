TYPE=VIEW
query=select `sur`.`id` AS `id`,`sur`.`user_id` AS `user_id`,`su`.`user_name` AS `user_name`,`su`.`real_name` AS `real_name`,`su`.`phone` AS `phone`,`su`.`email` AS `email`,`su`.`avatar` AS `avatar`,`sur`.`role_id` AS `role_id`,`sr`.`role_name` AS `role_name`,`sr`.`type` AS `type`,`sur`.`created_at` AS `created_at`,`sur`.`updated_at` AS `updated_at`,`sur`.`created_by` AS `created_by`,`sur`.`updated_by` AS `updated_by`,`sur`.`deleted` AS `deleted` from ((`alumin-link`.`sys_user_roles` `sur` join `alumin-link`.`sys_role` `sr` on(`sur`.`role_id` = `sr`.`id` and `sr`.`deleted` <> 1)) join `alumin-link`.`sys_user` `su` on(`sur`.`user_id` = `su`.`id` and `su`.`deleted` <> 1))
md5=6c2b241e048d17f486f1e4544e30b9b0
updatable=1
algorithm=0
definer_user=root
definer_host=localhost
suid=1
with_check_option=0
timestamp=0001794465027449245
create-version=2
source=select `sur`.`id` AS `id`,`sur`.`user_id` AS `user_id`,`su`.`user_name` AS `user_name`,`su`.`real_name` AS `real_name`,`su`.`phone` AS `phone`,`su`.`email` AS `email`,`su`.`avatar` AS `avatar`,`sur`.`role_id` AS `role_id`,`sr`.`role_name` AS `role_name`,`sr`.`type` AS `type`,`sur`.`created_at` AS `created_at`,`sur`.`updated_at` AS `updated_at`,`sur`.`created_by` AS `created_by`,`sur`.`updated_by` AS `updated_by`,`sur`.`deleted` AS `deleted` from ((`sys_user_roles` `sur` join `sys_role` `sr` on(`sur`.`role_id` = `sr`.`id` and `sr`.`deleted` <> 1)) join `sys_user` `su` on(`sur`.`user_id` = `su`.`id` and `su`.`deleted` <> 1))
client_cs_name=utf8mb4
connection_cl_name=utf8mb4_uca1400_ai_ci
view_body_utf8=select `sur`.`id` AS `id`,`sur`.`user_id` AS `user_id`,`su`.`user_name` AS `user_name`,`su`.`real_name` AS `real_name`,`su`.`phone` AS `phone`,`su`.`email` AS `email`,`su`.`avatar` AS `avatar`,`sur`.`role_id` AS `role_id`,`sr`.`role_name` AS `role_name`,`sr`.`type` AS `type`,`sur`.`created_at` AS `created_at`,`sur`.`updated_at` AS `updated_at`,`sur`.`created_by` AS `created_by`,`sur`.`updated_by` AS `updated_by`,`sur`.`deleted` AS `deleted` from ((`alumin-link`.`sys_user_roles` `sur` join `alumin-link`.`sys_role` `sr` on(`sur`.`role_id` = `sr`.`id` and `sr`.`deleted` <> 1)) join `alumin-link`.`sys_user` `su` on(`sur`.`user_id` = `su`.`id` and `su`.`deleted` <> 1))
mariadb-version=110413
