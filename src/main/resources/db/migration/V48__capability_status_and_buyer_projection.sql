-- V6-GATE0：能力状态需要容纳 REQUIRES_PLATFORM_PERMISSION（28字符）。
ALTER TABLE xianyu_account_capability
    MODIFY COLUMN status VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN';

-- 历史会话已具备 buyer_user_id 时，补齐买家关系读模型；不覆盖人工标签、备注或拦截设置。
INSERT INTO xianyu_buyer_profile
    (tenant_id, xianyu_account_id, buyer_user_id, buyer_user_name, last_interaction_time)
SELECT assignment.tenant_id,
       assignment.xianyu_account_id,
       assignment.buyer_user_id,
       COALESCE(
           (SELECT message.sender_user_name
              FROM xianyu_chat_message message
             WHERE message.tenant_id = assignment.tenant_id
               AND message.xianyu_account_id = assignment.xianyu_account_id
               AND message.s_id = assignment.session_id
               AND message.sender_user_id = assignment.buyer_user_id
             ORDER BY message.message_time DESC, message.id DESC LIMIT 1),
           (SELECT orders.buyer_user_name
              FROM xianyu_goods_order orders
             WHERE orders.tenant_id = assignment.tenant_id
               AND orders.xianyu_account_id = assignment.xianyu_account_id
               AND orders.buyer_user_id = assignment.buyer_user_id
             ORDER BY orders.create_time DESC, orders.id DESC LIMIT 1)
       ),
       assignment.last_message_time
  FROM conversation_assignment assignment
 WHERE assignment.buyer_user_id IS NOT NULL
   AND assignment.buyer_user_id <> ''
ON DUPLICATE KEY UPDATE
    buyer_user_name = COALESCE(NULLIF(VALUES(buyer_user_name), ''), buyer_user_name),
    last_interaction_time = GREATEST(
        COALESCE(last_interaction_time, VALUES(last_interaction_time)),
        VALUES(last_interaction_time)
    );
