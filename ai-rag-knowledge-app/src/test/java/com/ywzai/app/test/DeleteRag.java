package com.ywzai.app.test;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @Author: ywz
 * @CreateTime: 2025-09-16
 * @Description: 删除rag数据库
 * @Version: 1.0
 */
@Slf4j
@SpringBootTest
public class DeleteRag {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Test
    public void deleteRag(){
        String knowledgeTag = "贩罪";
        if (knowledgeTag == null || knowledgeTag.trim().isEmpty()) {
            return;
        }

        String sql = "DELETE FROM vector_store_openai WHERE metadata->>'knowledge' = ?";
        int deletedCount = jdbcTemplate.update(sql, knowledgeTag.trim());

        log.info("成功删除知识库 [{}] 下的 {} 条文档", knowledgeTag, deletedCount);

        // 可选：同时从 Redis 标签列表中移除
        RList<String> ragTagList = redissonClient.getList("ragTag");
        ragTagList.remove(knowledgeTag);
    }

}
